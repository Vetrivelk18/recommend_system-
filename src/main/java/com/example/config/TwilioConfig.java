package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Twilio's Messages API is Basic-Auth'd, form-encoded HTTP - same shape as Ollama and
 * the embed service, so this is the same plain-RestClient pattern as OllamaConfig/
 * EmbedConfig rather than pulling in Twilio's SDK for one endpoint.
 *
 * <p>Gated on webhook.provider=twilio, not just TwilioWebhookSender - a @Configuration
 * class is not conditional by default, so without this, twilio.account-sid/auth-token
 * would need to resolve at startup even while the stub sender is active.
 */
@Configuration
@ConditionalOnProperty(name = "webhook.provider", havingValue = "twilio")
public class TwilioConfig {

    @Bean
    public RestClient twilioRestClient(
            @Value("${twilio.account-sid}") String accountSid,
            @Value("${twilio.auth-token}") String authToken,
            @Value("${twilio.connect-timeout:5s}") Duration connectTimeout,
            @Value("${twilio.read-timeout:10s}") Duration readTimeout) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl("https://api.twilio.com")
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    request.getHeaders().setBasicAuth(accountSid, authToken);
                    return execution.execute(request, body);
                })
                .build();
    }
}
