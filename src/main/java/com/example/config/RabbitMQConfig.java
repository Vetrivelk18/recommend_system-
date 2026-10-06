package com.example.config;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitListenerRetrySettingsCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology for the recommendation job, and the policies deciding what happens to a message
 * that cannot be processed.
 *
 * <pre>
 *   producer --> recommendation.exchange --"recommendation.rerank"--> recommendation.queue
 *                                                                           |
 *                                                      rejected / retries exhausted
 *                                                                           v
 *                            recommendation.dlq <--"recommendation.failed"-- recommendation.dlx
 * </pre>
 *
 * <p>The producer names only the exchange and the routing key; the binding is what connects
 * those to a queue. A second consumer later - analytics, say - is one more binding and no
 * producer change.
 *
 * <p>These names replaced the original test.exchange / test.queue pair. New names rather
 * than new arguments on the old queue, because RabbitMQ rejects a redeclaration that
 * changes a queue's arguments with 406 PRECONDITION_FAILED - adding a dead-letter exchange
 * to an existing queue is not possible in place.
 */
@Configuration
public class RabbitMQConfig {

    /** Declared here and read by the producer and consumer, so the three cannot drift. */
    public static final String EXCHANGE = "recommendation.exchange";
    public static final String ROUTING_KEY = "recommendation.rerank";
    public static final String QUEUE = "recommendation.queue";

    public static final String DEAD_LETTER_EXCHANGE = "recommendation.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "recommendation.failed";
    public static final String DEAD_LETTER_QUEUE = "recommendation.dlq";

    /** A week is long enough to notice and investigate, short enough that a forgotten DLQ
     * cannot grow without bound. */
    private static final int DEAD_LETTER_TTL_MS = 7 * 24 * 60 * 60 * 1000;

    // ------------------------------------------------------------------ the working path

    /**
     * Durable, so a queued job survives a broker restart rather than dying with it.
     *
     * <p>The dead-letter arguments are what give "reject" somewhere to go. With
     * spring.rabbitmq.listener.simple.default-requeue-rejected=false, a message the consumer
     * rejects would simply be discarded; with these, the broker republishes it to the DLX
     * instead, so a failure is inspectable rather than merely gone.
     */
    @Bean
    public Queue recommendationQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public DirectExchange recommendationExchange() {
        return new DirectExchange(EXCHANGE);
    }

    @Bean
    public Binding recommendationBinding(Queue recommendationQueue,
                                         DirectExchange recommendationExchange) {
        return BindingBuilder
                .bind(recommendationQueue)
                .to(recommendationExchange)
                .with(ROUTING_KEY);
    }

    // -------------------------------------------------------------------- the failure path

    /**
     * Deliberately has no @RabbitListener. A consumer on the dead-letter queue is just a
     * slower retry loop, and these messages have already failed every attempt they are
     * going to get. It stays unconsumed so a person can look at what went wrong.
     */
    @Bean
    public Queue recommendationDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE)
                .ttl(DEAD_LETTER_TTL_MS)
                .build();
    }

    @Bean
    public DirectExchange recommendationDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Binding recommendationDeadLetterBinding(Queue recommendationDeadLetterQueue,
                                                   DirectExchange recommendationDeadLetterExchange) {
        return BindingBuilder
                .bind(recommendationDeadLetterQueue)
                .to(recommendationDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }

    // ------------------------------------------------------------------------- policies

    /**
     * JSON in both directions. Spring Boot applies a single MessageConverter bean to the
     * RabbitTemplate and to the listener container factory, so RecommendationJob is
     * serialised on the way out and reconstructed on the way in - the consumer receives the
     * record, not a String.
     */
    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /**
     * Stops the bounded retry from being spent on messages that can never succeed.
     *
     * <p>Without this, retry.enabled=true wraps the listener in a retry interceptor that
     * retries EVERY exception - including AmqpRejectAndDontRequeueException, whose whole
     * purpose is to say "do not try this again". A message with a null userId took three
     * attempts and ~14 seconds of a single-threaded consumer before being dropped, delaying
     * the real reranks queued behind it. Measured, not assumed: the log said "Retries
     * exhausted" for exactly that message.
     *
     * <p>The predicate walks the cause chain because the listener's exception arrives
     * wrapped in ListenerExecutionFailedException, so an instanceof test on the top-level
     * throwable would never match.
     */
    @Bean
    public RabbitListenerRetrySettingsCustomizer permanentFailuresAreNotRetried() {
        return settings -> settings.setExceptionPredicate(
                throwable -> !isPermanent(throwable));
    }

    /**
     * A malformed payload and a payload this application cannot act on are both permanent:
     * the same bytes will fail the same way on every attempt. Everything else - a database
     * blip, a dropped connection - is worth the three attempts. Both end up in the DLQ.
     */
    private static boolean isPermanent(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof AmqpRejectAndDontRequeueException
                    || cause instanceof MessageConversionException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;  // self-referencing cause, seen in some wrapped exceptions
            }
        }
        return false;
    }
}
