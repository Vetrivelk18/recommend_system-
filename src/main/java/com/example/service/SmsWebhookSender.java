package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Stands in for a real SMS provider - logs what would be sent instead of sending it.
 * Active by default (webhook.provider unset, or "stub"); set webhook.provider=twilio
 * to switch to TwilioWebhookSender instead. WebhookJob does not change either way.
 */
@Service
@ConditionalOnProperty(name = "webhook.provider", havingValue = "stub", matchIfMissing = true)
public class SmsWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(SmsWebhookSender.class);

    @Override
    public void send(String mobile, List<String> productNames) {
        log.info("[SMS stub] would text {}: {}", MobileNumbers.toE164(mobile), String.join(", ", productNames));
    }
}
