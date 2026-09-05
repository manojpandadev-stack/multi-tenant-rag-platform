package com.docmind.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async thread pool with trace-context propagation.
 *
 * The default Spring @Async executor does NOT propagate Micrometer/OpenTelemetry
 * trace context across thread boundaries. When a request triggers @Async processing,
 * the trace ID and span context are lost on the new thread — resulting in orphaned
 * traces that can't be correlated to the originating request.
 *
 * Fix: Use a TaskDecorator that captures the current trace context before the async
 * dispatch and restores it on the worker thread. This ensures a single trace ID
 * appears in spans on both sides of the async boundary.
 *
 * Bounded pool (core=2, max=8, queue=50):
 * - Prevents upload bursts from starving Tomcat request-handling threads
 * - CallerRunsPolicy provides back-pressure (slows uploads, never drops tasks)
 * - At scale: replace with Kafka/SQS + separate consumer service
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean("documentProcessingExecutor")
    public Executor documentProcessingExecutor(Tracer tracer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-ingest-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Propagate trace context across async boundary
        executor.setTaskDecorator(traceContextDecorator(tracer));

        executor.initialize();
        return executor;
    }

    /**
     * TaskDecorator that captures the current trace context and restores it
     * on the async worker thread. This is the key to making OpenTelemetry
     * traces span across @Async boundaries.
     */
    private TaskDecorator traceContextDecorator(Tracer tracer) {
        return new TaskDecorator() {
            @NonNull
            @Override
            public Runnable decorate(@NonNull Runnable runnable) {
                // Capture current span on the calling thread (thread-local)
                Span currentSpan = tracer.currentSpan();

                return () -> {
                    // Restore span on the worker thread using Tracer.withSpan()
                    // This sets the span as current for the duration of this block,
                    // so any child spans created here inherit the same trace ID.
                    try (Tracer.SpanInScope ignored = currentSpan != null
                            ? tracer.withSpan(currentSpan) : null) {
                        if (currentSpan != null) {
                            log.debug("Restored trace context on async thread: traceId={}",
                                    currentSpan.context().traceId());
                        }
                        runnable.run();
                    }
                };
            }
        };
    }
}
