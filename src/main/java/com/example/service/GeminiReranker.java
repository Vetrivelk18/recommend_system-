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
 * Reranking against Gemini - what production runs on, since a 14B model on the same host
 * is not something a free-tier cloud instance can offer.
 *
 * <p>Structurally the same job as OllamaReranker: post the shared prompt with the shared
 * schema, read ids back, hand them to the shared validation. Only the request shape and the
 * path to the generated text differ.
 */
@Service
@ConditionalOnProperty(name = "reranker.provider", havingValue = "gemini")
public class GeminiReranker implements ProductReranker {

    private static final Logger log = LoggerFactory.getLogger(GeminiReranker.class);

    private final RestClient client;
    private final ObjectMapper mapper;
    private final String model;

    public GeminiReranker(RestClient geminiRestClient,
                          ObjectMapper mapper,
                          @Value("${gemini.model}") String model) {
        this.client = geminiRestClient;
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
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(userContext, candidates, limit))
                    .retrieve()
                    .body(JsonNode.class);

            return RerankSupport.validate(extractIds(response), candidates, limit);

        } catch (Exception e) {
            // Its own log line rather than a shared one, because the likely causes differ:
            // a 429 from the free tier's rate limit, or an expired key, versus Ollama simply
            // not running. Both degrade to candidate order with nothing user-visible, so the
            // log is the only place this shows up - grep "gemini rerank failed".
            log.warn("gemini rerank failed, falling back to candidate order", e);
            return RerankSupport.fallback(candidates, limit);
        }
    }

    private Map<String, Object> requestBody(String userContext, List<RerankCandidate> candidates, int limit) {
        return Map.of(
                "systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", RerankSupport.SYSTEM))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of(
                                "text", RerankSupport.userPrompt(userContext, candidates, limit))))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", RerankSupport.responseSchema(limit),
                        // Picking products is a judgement to be made the same way twice, not
                        // a creative task - and a deterministic reranker is far easier to
                        // reason about when its output looks wrong.
                        "temperature", 0));
    }

    /**
     * Gemini nests the generated text at candidates[0].content.parts[0].text, and like
     * Ollama that text is itself the JSON object - so this parses twice as well.
     *
     * <p>A response can legitimately carry no parts at all: a safety block finishes with
     * finishReason SAFETY and omits content. Treated as a failure so the caller falls back,
     * rather than reading an empty string and parsing nothing.
     */
    private List<Integer> extractIds(JsonNode response) {
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            String finishReason = response.path("candidates").path(0).path("finishReason").asText("unknown");
            throw new IllegalStateException("gemini returned no content, finishReason=" + finishReason);
        }

        JsonNode ids = mapper.readTree(parts.path(0).path("text").asText()).path("product_ids");

        List<Integer> parsed = new ArrayList<>();
        ids.forEach(node -> parsed.add(node.asInt()));
        return parsed;
    }
}
