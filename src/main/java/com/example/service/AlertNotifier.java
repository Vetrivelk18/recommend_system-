package com.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Posts an alert to an incoming-webhook URL - Slack, Discord, Teams, or anything that
 * accepts a JSON POST.
 *
 * <p>Off unless alert.webhook-url is set, so the default configuration and local
 * development are unaffected: nothing is sent and nothing can fail.
 *
 * <p>Builds its own RestClient rather than taking one from a @Configuration class the way
 * EmbedConfig and OllamaConfig do. The client should exist only when a URL is configured,
 * and one branch in a constructor is less machinery than a conditional bean plus
 * ObjectProvider injection at the other end.
 */
@Service
public class AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    /** Null when unconfigured, which is also how isEnabled() answers. */
    private final RestClient client;

    /**
     * The body is serialised here rather than handed to RestClient as a Map, so the request
     * carries a Content-Length instead of going out chunked - see the request factory below.
     * tools.jackson, not com.fasterxml: Spring Boot 4.1 ships Jackson 3.
     */
    private final ObjectMapper objectMapper;

    public AlertNotifier(ObjectMapper objectMapper,
                         @Value("${alert.webhook-url:}") String webhookUrl,
                         @Value("${alert.connect-timeout:3s}") Duration connectTimeout,
                         @Value("${alert.read-timeout:5s}") Duration readTimeout) {

        this.objectMapper = objectMapper;

        if (webhookUrl == null || webhookUrl.isBlank()) {
            this.client = null;
            log.info("alerting disabled - alert.webhook-url is not set");
            return;
        }

        // JdkClientHttpRequestFactory, not the SimpleClientHttpRequestFactory that
        // EmbedConfig and OllamaConfig use. Those stream the body, so the request goes out
        // with Transfer-Encoding: chunked and no Content-Length - fine for the local
        // services they call, but this one talks to the public internet, where some proxies
        // and WAFs reject a chunked request body.
        //
        // Swapping the factory is only half of it: the JDK client sets a Content-Length only
        // when the request already knows its length, which a streamed Map->JSON conversion
        // never does. That is why send() serialises to a String first - a String body has a
        // known length, so the header gets set. Verified on the wire; the factory alone still
        // produced chunked.
        // Pinned to HTTP/1.1. The JDK client otherwise advertises "Upgrade: h2c" on every
        // request, attempting an HTTP/2 upgrade the previous factory never did. Mainstream
        // webhook endpoints handle that fine, but it is a gratuitous difference on a call
        // whose only job is to deliver one small POST to someone else's server.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);

        this.client = RestClient.builder()
                .baseUrl(webhookUrl)
                .requestFactory(factory)
                .build();

        // The URL itself is a credential - anyone holding it can post into the channel -
        // so it is never logged, here or anywhere else.
        log.info("alerting enabled");
    }

    public boolean isEnabled() {
        return client != null;
    }

    /**
     * Fire-and-forget. A failed alert is logged and dropped: the WARN that triggered it is
     * already in the log, so a broken webhook costs visibility, never availability. Retrying
     * would only risk blocking the scheduler thread this runs on.
     *
     * @param status FIRING or RESOLVED, so a receiver can pair the two
     */
    public void send(String status, String summary, Map<String, Object> details) {
        if (client == null) {
            return;
        }

        // "text" is what Slack renders, "content" is what Discord renders; sending both
        // makes one payload work with either without a per-vendor adapter. Anything else
        // can read the structured fields.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", summary);
        body.put("content", summary);
        body.put("status", status);
        body.put("application", "basket");
        body.put("timestamp", Instant.now().toString());
        body.putAll(details);

        try {
            client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .toBodilessEntity();
            log.info("alert delivered: [{}] {}", status, summary);
        } catch (Exception e) {
            log.warn("alert delivery failed - the warning it was raising is still in the log: {}",
                    e.toString());
        }
    }
}
