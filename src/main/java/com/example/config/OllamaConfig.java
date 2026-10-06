package com.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class OllamaConfig {

    /**
     * Ollama needs no SDK - it is a JSON HTTP API - so this is a plain RestClient.
     *
     * <p>The read timeout is generous because a 14B model on CPU or shared GPU can
     * take tens of seconds for a single rerank. That is acceptable here only because
     * the caller is a batch job that already tolerates a delay; anything on a request
     * path would need a much tighter budget and a fallback.
     */
    @Bean
    public RestClient ollamaRestClient(
            @Value("${ollama.base-url}") String baseUrl,
            @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
            @Value("${ollama.read-timeout:120s}") Duration readTimeout) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
