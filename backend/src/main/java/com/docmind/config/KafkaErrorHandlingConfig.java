package com.docmind.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka listener error handling: max-retry-then-DLQ.
 *
 * Two distinct failure domains are handled differently:
 *
 * 1. Business/processing failures (bad PDF, embedding API down, DB constraint):
 *    DocumentProcessingPipeline catches these internally and marks the document
 *    FAILED with an error message — the DB row is the durable outcome. The
 *    listener returns normally, so these records are NOT retried or dead-lettered.
 *
 * 2. Infrastructure/poison failures (deserialization error, listener throws,
 *    transient DB outage): the listener throws, this handler retries with
 *    exponential backoff (1s, 2s, 4s — 3 attempts), then publishes the record
 *    to {@code document-uploaded.DLT} and acknowledges it. Nothing is silently
 *    dropped, and one poison record cannot block the partition head.
 *
 * Spring Boot auto-wires a {@link CommonErrorHandler} bean into the default
 * listener container factory, so no manual factory customization is needed.
 */
@Configuration
public class KafkaErrorHandlingConfig {

    /** Max retries before dead-lettering (3 attempts total: initial + 2 retries). */
    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final double MULTIPLIER = 2.0;
    private static final long MAX_ELAPSED_MS = 8000L;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, Object> kafkaTemplate) {
        // Publishes failed records to "<original-topic>.DLT" (document-uploaded.DLT)
        // preserving the original partition when it exists on the DLT topic.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    // Route every dead letter to the single DLT topic, partition 0
                    // (the DLT is auto-created with 1 partition).
                    return new TopicPartition(record.topic() + ".DLT", 0);
                });

        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, MULTIPLIER);
        backOff.setMaxElapsedTime(MAX_ELAPSED_MS);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
