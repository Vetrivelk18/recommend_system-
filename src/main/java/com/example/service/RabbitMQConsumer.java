package com.example.service;

import com.example.config.RabbitMQConfig;
import com.example.model.RecommendationJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Where the rerank actually happens, off the request thread.
 *
 * <p>Calls topFor rather than the old @Async warm() on purpose. An @Async method returns
 * immediately, so the listener would finish and the broker would ack the message while the
 * real work was still queued somewhere else - every delivery guarantee would be fiction and
 * a crash would lose the job silently. topFor is synchronous, so the ack means the rerank
 * genuinely completed.
 *
 * <p>Nothing downstream changes: topFor still does its own cache-aside, single-flight
 * dedup, and "discard if the user searched again" check. RabbitMQ replaced how the work is
 * dispatched, not what it does.
 */
@Service
public class RabbitMQConsumer {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConsumer.class);

    private final RecommendationService recommendationService;

    public RabbitMQConsumer(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /**
     * Two failure classes, handled differently on purpose.
     *
     * <p>A payload that can never succeed - no user id, or an id too large for the Integer
     * the rest of the stack uses - throws AmqpRejectAndDontRequeueException. On its own
     * that exception does NOT skip the retries: with retry.enabled=true the interceptor
     * retries every exception and only the recoverer afterwards honours it. The predicate
     * in RabbitMQConfig.permanentFailuresAreNotRetried is what makes it fail on the first
     * attempt instead of burning three.
     *
     * <p>Either way the message is rejected rather than requeued, and the queue's
     * dead-letter arguments send it to recommendation.dlq, where it can be inspected.
     *
     * <p>Anything else (Ollama down, database unreachable) is allowed to propagate, so the
     * listener's bounded retry gets its three attempts before the message is rejected. It
     * is then dropped rather than requeued, because this job is a disposable cache warm:
     * if it never runs, RecommendationController simply recomputes on the next real read.
     * Requeuing instead would redeliver forever, at 30-45 seconds of model time per cycle.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE)
    public void onRecommendationJob(RecommendationJob job) {
        if (job == null || job.userId() == null) {
            throw new AmqpRejectAndDontRequeueException("job has no userId: " + job);
        }

        int userId;
        try {
            userId = Math.toIntExact(job.userId());
        } catch (ArithmeticException e) {
            throw new AmqpRejectAndDontRequeueException(
                    "userId " + job.userId() + " does not fit the Integer ids this app uses", e);
        }

        // The query rides along for traceability only. topFor re-reads the current query
        // from last_search itself, which is what lets it notice the shopper searched again
        // while the rerank was running - trusting the message instead would break that.
        log.info("consuming recommendation job for user {} (query \"{}\")", userId, job.query());

        long started = System.currentTimeMillis();
        List<Integer> picks = recommendationService.topFor(userId);
        long tookMs = System.currentTimeMillis() - started;

        log.info("recommendation job done for user {}: {} picks in {} ms", userId, picks.size(), tookMs);
    }
}
