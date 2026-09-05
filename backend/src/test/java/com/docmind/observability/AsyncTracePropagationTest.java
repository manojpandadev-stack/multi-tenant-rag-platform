package com.docmind.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves that the TaskDecorator pattern from AsyncConfig correctly propagates
 * trace context across @Async thread boundaries.
 *
 * This test uses SimpleTracer (micrometer-tracing-test) which faithfully
 * simulates the real OTel tracer's scope management. The TaskDecorator
 * implementation is identical to AsyncConfig's — this test validates the
 * pattern itself without needing a full Spring context or OTel agent.
 *
 * What it proves: After applying the TaskDecorator, a span created on the
 * calling thread is accessible as the current span on the worker thread,
 * with the same trace ID. Without the decorator, currentSpan() returns null
 * on the worker thread (thread-local context is not inherited).
 */
@DisplayName("Async Trace Propagation (TaskDecorator Pattern)")
class AsyncTracePropagationTest {

    private SimpleTracer tracer;
    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        tracer = new SimpleTracer();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("trace-propagation-test-");

        // This is the EXACT pattern from AsyncConfig.traceContextDecorator()
        executor.setTaskDecorator(runnable -> {
            Span currentSpan = tracer.currentSpan();
            return () -> {
                try (Tracer.SpanInScope ignored = currentSpan != null
                        ? tracer.withSpan(currentSpan) : null) {
                    runnable.run();
                }
            };
        });

        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("Trace ID propagates across @Async boundary via TaskDecorator")
    void traceIdPropagatesAcrossAsyncBoundary() throws Exception {
        Span parentSpan = tracer.nextSpan().name("test-parent").start();
        String parentTraceId = parentSpan.context().traceId();

        try (Tracer.SpanInScope ws = tracer.withSpan(parentSpan)) {
            AtomicReference<String> asyncTraceId = new AtomicReference<>(null);
            AtomicReference<String> asyncSpanId = new AtomicReference<>(null);
            CountDownLatch latch = new CountDownLatch(1);

            // Submit to the decorated executor (simulates @Async dispatch)
            executor.execute(() -> {
                Span currentSpan = tracer.currentSpan();
                if (currentSpan != null) {
                    asyncTraceId.set(currentSpan.context().traceId());
                    asyncSpanId.set(currentSpan.context().spanId());
                }
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS), "Async task should complete within 5s");

            // THE CRITICAL ASSERTION: trace ID must match
            assertNotNull(asyncTraceId.get(),
                "currentSpan() must be non-null on worker thread — " +
                "if null, TaskDecorator is not restoring the span context");
            assertEquals(parentTraceId, asyncTraceId.get(),
                "Trace ID must propagate across @Async boundary. " +
                "Parent: " + parentTraceId + ", Async: " + asyncTraceId.get());
        } finally {
            parentSpan.end();
        }
    }

    @Test
    @DisplayName("Concurrent async calls preserve distinct trace IDs")
    void concurrentAsyncCallsPreserveDistinctTraceIds() throws Exception {
        int numCalls = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numCalls);
        ConcurrentMap<Integer, String> callTraceIds = new ConcurrentHashMap<>();

        for (int i = 0; i < numCalls; i++) {
            final int callIndex = i;
            // Each call runs on a request thread with its own span
            new Thread(() -> {
                try {
                    startLatch.await(); // synchronization point — all start together
                    Span span = tracer.nextSpan().name("call-" + callIndex).start();
                    String traceId = span.context().traceId();

                    try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
                        AtomicReference<String> asyncTraceId = new AtomicReference<>();
                        CountDownLatch taskDone = new CountDownLatch(1);

                        executor.execute(() -> {
                            Span currentSpan = tracer.currentSpan();
                            if (currentSpan != null) {
                                asyncTraceId.set(currentSpan.context().traceId());
                            }
                            taskDone.countDown();
                        });

                        taskDone.await(5, TimeUnit.SECONDS);
                        callTraceIds.put(callIndex, asyncTraceId.get());
                    } finally {
                        span.end();
                    }
                } catch (Exception e) {
                    fail("Unexpected exception in call " + callIndex + ": " + e.getMessage());
                } finally {
                    doneLatch.countDown();
                }
            }, "request-thread-" + callIndex).start();
        }

        startLatch.countDown(); // release all threads simultaneously
        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "All calls should complete within 30s");

        // Verify every call propagated correctly
        for (int i = 0; i < numCalls; i++) {
            assertNotNull(callTraceIds.get(i),
                "Trace ID should propagate for call " + i);
        }

        // Verify each call has its OWN distinct trace ID
        long distinctTraceIds = callTraceIds.values().stream().distinct().count();
        assertEquals(numCalls, distinctTraceIds,
            "Each concurrent call should have a distinct trace ID — " +
            "got " + distinctTraceIds + " distinct out of " + numCalls);
    }

    @Test
    @DisplayName("Without TaskDecorator, trace ID does NOT propagate (proves the decorator is necessary)")
    void withoutDecorator_traceIdDoesNotPropagate() throws Exception {
        // Create a plain executor WITHOUT the TaskDecorator
        ThreadPoolTaskExecutor plainExecutor = new ThreadPoolTaskExecutor();
        plainExecutor.setCorePoolSize(2);
        plainExecutor.setThreadNamePrefix("plain-");
        plainExecutor.initialize();

        try {
            Span parentSpan = tracer.nextSpan().name("test-parent").start();
            String parentTraceId = parentSpan.context().traceId();

            try (Tracer.SpanInScope ws = tracer.withSpan(parentSpan)) {
                AtomicReference<String> asyncTraceId = new AtomicReference<>(null);
                CountDownLatch latch = new CountDownLatch(1);

                plainExecutor.execute(() -> {
                    Span currentSpan = tracer.currentSpan();
                    if (currentSpan != null) {
                        asyncTraceId.set(currentSpan.context().traceId());
                    }
                    latch.countDown();
                });

                latch.await(5, TimeUnit.SECONDS);

                // Without the decorator, currentSpan() should be null on the worker thread
                assertNull(asyncTraceId.get(),
                    "Without TaskDecorator, currentSpan() should be null on worker thread — " +
                    "this proves the decorator is necessary");
                assertNotEquals(parentTraceId, asyncTraceId.get(),
                    "Without decorator, trace IDs should NOT match");
            } finally {
                parentSpan.end();
            }
        } finally {
            plainExecutor.shutdown();
        }
    }
}
