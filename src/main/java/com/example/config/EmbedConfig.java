package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * RestClient for the embed microservice (ml/embed_service.py). Same pattern as
 * OllamaConfig: plain HTTP, no SDK, because the service is just a JSON endpoint.
 *
 * <p>Timeouts are far tighter than Ollama's - this is one BGE forward pass on a
 * 384-d model, milliseconds, not the tens of seconds a 14B LLM call takes. A search
 * box request is also latency-sensitive in a way the batch reranker is not.
 */
@Configuration
public class EmbedConfig {

    @Bean
    public RestClient embedRestClient(
            @Value("${embed.base-url}") String baseUrl,
            @Value("${embed.connect-timeout:3s}") Duration connectTimeout,
            @Value("${embed.read-timeout:10s}") Duration readTimeout) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
