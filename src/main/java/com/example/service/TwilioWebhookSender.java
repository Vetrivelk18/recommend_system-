package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Real send via Twilio's Messages API. Active only when webhook.provider=twilio -
 * see SmsWebhookSender for the stub this replaces. WebhookJob's per-user try/catch
 * already handles a failed send (unverified number, bad geo permissions, etc.) by
 * logging and leaving the row for the next poll - nothing extra needed here.
 */
@Service
@ConditionalOnProperty(name = "webhook.provider", havingValue = "twilio")
public class TwilioWebhookSender implements WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioWebhookSender.class);

    private final RestClient client;
    private final String accountSid;
    private final String fromNumber;

    public TwilioWebhookSender(RestClient twilioRestClient,
                                @Value("${twilio.account-sid}") String accountSid,
                                @Value("${twilio.from-number}") String fromNumber) {
        this.client = twilioRestClient;
        this.accountSid = accountSid;
        this.fromNumber = fromNumber;
    }

    @Override
    public void send(String mobile, List<String> productNames) {
        String to = MobileNumbers.toE164(mobile);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("To", to);
        form.add("From", fromNumber);
        form.add("Body", "Here are a few things you might want: " + String.join(", ", productNames));

        client.post()
                .uri("/2010-04-01/Accounts/{sid}/Messages.json", accountSid)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .toBodilessEntity();

        log.info("SMS sent to {}", to);
    }
}
