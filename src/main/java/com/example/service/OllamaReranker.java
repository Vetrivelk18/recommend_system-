package com.example.service;

import com.example.dto.RerankCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reranking against a local Ollama daemon - the default, and what development runs on.
 *
 * <p>The condition is load-bearing: GeminiReranker implements the same interface, and two
 * unconditional beans would fail startup with NoUniqueBeanDefinitionException.
 * matchIfMissing keeps this the default, so an unset property behaves as it always did.
 *
 * <p>Everything except the HTTP call lives in RerankSupport, shared with the Gemini path so
 * a prompt change reaches both.
 */
@Service
@ConditionalOnProperty(name = "reranker.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaReranker implements ProductReranker {

    private static final Logger log = LoggerFactory.getLogger(OllamaReranker.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;

    public OllamaReranker(RestClient ollamaRestClient,
                          ObjectMapper mapper,
                          @Value("${ollama.model}") String model) {
        this.client = ollamaRestClient;
        this.mapper = mapper;
        this.model = model;
    }

    @Override
    public List<Integer> rerank(String userContext, List<RerankCandidate> candidates, int limit) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (RerankSupport.nothingToDecide(candidates, limit)) {
            return RerankSupport.fallback(candidates, limit);
        }

        try {
            JsonNode response = client.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(userContext, candidates, limit))
                    .retrieve()
                    .body(JsonNode.class);

            return RerankSupport.validate(extractIds(response), candidates, limit);

        } catch (Exception e) {
            log.warn("ollama rerank failed, falling back to candidate order", e);
            return RerankSupport.fallback(candidates, limit);
        }
    }

    private Map<String, Object> requestBody(String userContext, List<RerankCandidate> candidates, int limit) {
        return Map.of(
                "model", model,
                "stream", false,
                // Ollama constrains generation to this schema, so the reply parses without
                // any prose-stripping.
                "format", RerankSupport.responseSchema(limit),
                "messages", List.of(
                        Map.of("role", "system", "content", RerankSupport.SYSTEM),
                        Map.of("role", "user", "content",
                                RerankSupport.userPrompt(userContext, candidates, limit))));
    }

    /** Ollama returns the assistant text as a string that itself holds the JSON object. */
    private List<Integer> extractIds(JsonNode response) {
        String content = response.path("message").path("content").asText();
        JsonNode ids = mapper.readTree(content).path("product_ids");

        List<Integer> parsed = new ArrayList<>();
        ids.forEach(node -> parsed.add(node.asInt()));
        return parsed;
    }
}
