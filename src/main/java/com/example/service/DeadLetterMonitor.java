package com.example.service;

import com.example.config.RabbitMQConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tells you the dead-letter queue is filling up, which nothing otherwise would.
 *
 * <p>recommendation.dlq has no consumer by design - a listener on it would just be a slower
 * retry loop - so without this the only way to notice a failed job is to go and look. A
 * silent queue that only matters when it is non-empty is exactly the thing to put a check
 * on.
 *
 * <p>Depth comes from AmqpAdmin over the connection the application already holds, not from
 * the management plugin's HTTP API. That keeps it working where the plugin is disabled and
 * avoids a second set of credentials.
 */
@Service
public class DeadLetterMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMonitor.class);

    private final AmqpAdmin amqpAdmin;
    private final AlertNotifier alertNotifier;
    private final long warnThreshold;
    private final Duration alertCooldown;

    /**
     * The scheduled check writes here and the gauge reads it, rather than the gauge calling
     * the broker itself. A Micrometer gauge is sampled on every scrape, so binding it
     * straight to a broker call would turn a monitoring system's polling rate into load on
     * RabbitMQ. One call per interval, however often the metric is read.
     */
    private final AtomicLong depth = new AtomicLong();

    /** Remembered so the "back to empty" line is logged once rather than every interval. */
    private long previousDepth;

    /** Null while nothing is firing. Also the flag for whether a RESOLVED is owed. */
    private Instant alertedAt;

    public DeadLetterMonitor(AmqpAdmin amqpAdmin,
                             AlertNotifier alertNotifier,
                             MeterRegistry meterRegistry,
                             @Value("${rabbitmq.dlq.warn-threshold}") long warnThreshold,
                             @Value("${alert.cooldown}") Duration alertCooldown) {
        this.amqpAdmin = amqpAdmin;
        this.alertNotifier = alertNotifier;
        this.warnThreshold = warnThreshold;
        this.alertCooldown = alertCooldown;

        Gauge.builder("recommendation.dlq.depth", depth, AtomicLong::doubleValue)
                .description("Messages waiting in the recommendation dead-letter queue")
                .register(meterRegistry);
    }

    /**
     * Quiet while the queue is empty - a line every interval saying "0" is noise that
     * teaches people to ignore the log. Only a backlog, and the moment one clears, is worth
     * printing.
     *
     * <p>Swallows its own failures for the same reason the rest of this codebase does: a
     * monitoring check must never be the thing that breaks the application, and an
     * unreachable broker is already visible elsewhere.
     */
    @Scheduled(fixedDelayString = "${rabbitmq.dlq.check-interval}")
    public void checkDepth() {
        long current;
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(RabbitMQConfig.DEAD_LETTER_QUEUE);
            if (info == null) {
                // Declared at startup, so this means the queue was deleted underneath us.
                log.warn("dead-letter queue {} does not exist - failed jobs have nowhere to go",
                        RabbitMQConfig.DEAD_LETTER_QUEUE);
                return;
            }
            current = info.getMessageCount();
        } catch (Exception e) {
            log.warn("could not read {} depth - leaving the gauge at its last value: {}",
                    RabbitMQConfig.DEAD_LETTER_QUEUE, e.toString());
            return;
        }

        depth.set(current);

        if (current >= warnThreshold) {
            log.warn("dead-letter queue {} holds {} failed recommendation job(s) - inspect with "
                            + "GET /api/queues/%2F/{}/get (ackmode reject_requeue_true, or you consume them)",
                    RabbitMQConfig.DEAD_LETTER_QUEUE, current, RabbitMQConfig.DEAD_LETTER_QUEUE);
            maybeAlert(current);
        } else if (previousDepth >= warnThreshold) {
            log.info("dead-letter queue {} is back to {} - the backlog cleared",
                    RabbitMQConfig.DEAD_LETTER_QUEUE, current);
            resolveAlert(current);
        }

        previousDepth = current;
    }

    /**
     * Fires on the first crossing of the threshold, then at most once per cooldown while the
     * backlog persists.
     *
     * <p>The check above runs every few minutes and warns on every tick, so alerting
     * straight off it would send a notification every interval until someone cleared the
     * queue - the surest way to get a channel muted. A problem left alone should nag
     * hourly, not continuously.
     *
     * <p>Known limitation: a backlog that worsens inside the cooldown window does not
     * re-alert. Alerting on growth as well would need another threshold to tune, and the
     * hourly repeat already carries the current depth.
     */
    private void maybeAlert(long current) {
        Instant now = Instant.now();
        if (alertedAt != null && now.isBefore(alertedAt.plus(alertCooldown))) {
            return;
        }

        boolean repeat = alertedAt != null;
        alertedAt = now;

        alertNotifier.send("FIRING",
                (repeat ? "Still failing: " : "")
                        + current + " recommendation job(s) in " + RabbitMQConfig.DEAD_LETTER_QUEUE,
                Map.of("queue", RabbitMQConfig.DEAD_LETTER_QUEUE,
                        "depth", current,
                        "threshold", warnThreshold));
    }

    /** Only sent if something actually fired, so a receiver never gets an orphan RESOLVED. */
    private void resolveAlert(long current) {
        if (alertedAt == null) {
            return;
        }
        alertedAt = null;

        alertNotifier.send("RESOLVED",
                RabbitMQConfig.DEAD_LETTER_QUEUE + " is back to " + current,
                Map.of("queue", RabbitMQConfig.DEAD_LETTER_QUEUE,
                        "depth", current,
                        "threshold", warnThreshold));
    }
}
