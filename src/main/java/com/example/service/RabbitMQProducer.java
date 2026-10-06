package com.example.service;

import com.example.config.RabbitMQConfig;
import com.example.model.RecommendationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes one recommendation job per authenticated search.
 *
 * <p>The producer knows the exchange and the routing key and nothing else - not the queue,
 * not the consumer. recommendation.exchange is a DirectExchange, so the broker matches the
 * routing key against its bindings and drops the message there. Adding a second consumer
 * later is a new binding, with no change here.
 *
 * <p>Both names come from RabbitMQConfig rather than being repeated here, so a rename
 * cannot leave the producer publishing into an exchange nothing is bound to.
 *
 * <p>Serialisation is JSON: RabbitMQConfig declares a JacksonJsonMessageConverter bean and
 * Spring Boot wires it into both the RabbitTemplate and the listener container, so the
 * record goes out as JSON and comes back as a RecommendationJob.
 */
@Service
public class RabbitMQProducer {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public RabbitMQProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Fails open, deliberately and for the same reason every Redis call in this project
     * does: the caller is a search request that has already produced its results, and the
     * job is only a cache warm. An unreachable broker should cost the shopper a slower
     * first read of their recommendations, never a failed search.
     *
     * <p>Catching AmqpException rather than Exception keeps genuine programming errors
     * visible instead of swallowing them with the connection failures.
     */
    public void sendRecommendationJob(Long userId, String query) {
        RecommendationJob job = new RecommendationJob(userId, query);

        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, job);
            log.info("published recommendation job {} -> {}/{}",
                    job, RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY);
        } catch (AmqpException e) {
            log.warn("could not publish recommendation job {} - the next read recomputes: {}",
                    job, e.toString());
        }
    }
}
