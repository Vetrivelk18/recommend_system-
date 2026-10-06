package com.example.controller;

import com.example.service.RabbitMQProducer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes a job without going through a search.
 *
 * <p>Kept because /api/search is not always a usable trigger: a cache miss there needs the
 * embed microservice on :8001, so when that is not running the search fails before it ever
 * reaches the producer. This endpoint exercises producer -> exchange -> queue -> consumer
 * on its own.
 *
 * <p>It publishes the same RecommendationJob the search path does, so the consumer cannot
 * tell the two apart - this is a different way in, not a different message.
 */
@RestController
public class RabbitMQController {

    private final RabbitMQProducer producer;

    public RabbitMQController(RabbitMQProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/rabbit/send")
    public Map<String, Object> send(@RequestParam Long userId,
                                    @RequestParam(required = false) String q) {
        producer.sendRecommendationJob(userId, q);

        // "published", not "processed": the response returns as soon as the broker has the
        // message. Whether the rerank succeeded is in the consumer's log, not here.
        return Map.of("published", true, "userId", userId, "query", q == null ? "" : q);
    }
}
