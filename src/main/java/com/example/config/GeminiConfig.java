package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * RestClient for Google's Generative Language API. Same plain-HTTP approach as OllamaConfig
 * and EmbedConfig - one JSON endpoint does not justify an SDK.
 *
 * <p>Only created when reranker.provider=gemini, so the key is not required for a
 * development run on Ollama.
 */
@Configuration
@ConditionalOnProperty(name = "reranker.provider", havingValue = "gemini")
public class GeminiConfig {

    /**
     * The key goes in the x-goog-api-key header, not the ?key= query parameter the REST
     * docs show. Both authenticate identically, but a credential in a URL is copied into
     * access logs, proxy logs and the text of error messages - a header is not.
     *
     * <p>Timeouts are far tighter than Ollama's 120s: a hosted model answers in seconds, and
     * failing fast here just means falling back to candidate order a little sooner.
     */
    @Bean
    public RestClient geminiRestClient(
            @Value("${gemini.base-url}") String baseUrl,
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.connect-timeout:5s}") Duration connectTimeout,
            @Value("${gemini.read-timeout:30s}") Duration readTimeout) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-goog-api-key", apiKey)
                .requestFactory(factory)
                .build();
    }
}
