package com.example.service;

import com.example.dto.RerankCandidate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OllamaReranker implements ProductReranker {

    private static final Logger log = LoggerFactory.getLogger(OllamaReranker.class);

    private static final String SYSTEM = """
            You choose which grocery products to send a shopper in a follow-up message.
            You are given the shopper's context and a numbered candidate list.

            Each candidate is tagged with where it came from:
              QUERY       - matches what they searched for this session
              BEHAVIOURAL - people with similar buying patterns bought it
              BOTH        - both rails surfaced it, which is the strongest signal

            Judge every candidate on the shopper's context, not on how many candidates
            share a category. The behavioural rail over-produces a few departments, so a
            long run of similar items is an artefact of how candidates were generated and
            not evidence the shopper wants them.

            Prefer variety over near-duplicates: five versions of one product is a worse
            message than four plus something they plausibly also want.

            Return only product ids drawn from the candidate list.
            """;

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
        if (candidates.size() <= limit) {
            return candidates.stream().map(RerankCandidate::productId).toList();
        }

        try {
            JsonNode response = client.post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody(userContext, candidates, limit))
                    .retrieve()
                    .body(JsonNode.class);

            return validate(extractIds(response), candidates, limit);

        } catch (Exception e) {
            // A webhook that sends the top few candidates is worse than one the model
            // curated, but far better than one that never fires. The candidate lists
            // arrive already ranked, so falling back to their order degrades quality
            // rather than correctness.
            log.warn("rerank failed, falling back to candidate order", e);
            return candidates.stream().map(RerankCandidate::productId).limit(limit).toList();
        }
    }

    private Map<String, Object> requestBody(String userContext, List<RerankCandidate> candidates, int limit) {
        String list = candidates.stream()
                .map(c -> "%d\t%s\t[%s]".formatted(c.productId(), c.product(), c.source()))
                .collect(Collectors.joining("\n"));

        return Map.of(
                "model", model,
                "stream", false,
                // Ollama constrains generation to this schema, so the reply parses without
                // any prose-stripping. It does NOT constrain the ids to real products -
                // that is what validate() is for.
                "format", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "product_ids", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "integer"),
                                        "minItems", limit,
                                        "maxItems", limit)),
                        "required", List.of("product_ids")),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM),
                        Map.of("role", "user", "content",
                                "Shopper:\n%s\n\nCandidates (id, product, source):\n%s\n\nPick %d."
                                        .formatted(userContext, list, limit))));
    }

    /** Ollama returns the assistant text as a string that itself holds the JSON object. */
    private List<Integer> extractIds(JsonNode response) {
        String content = response.path("message").path("content").asText();
        JsonNode ids = mapper.readTree(content).path("product_ids");

        List<Integer> parsed = new ArrayList<>();
        ids.forEach(node -> parsed.add(node.asInt()));
        return parsed;
    }

    /**
     * The schema guarantees shape, not truth - a model can return a well-formed id that
     * is not in the candidate set, or repeat one. Both are silent failures downstream,
     * so unknown ids are dropped and any shortfall is topped up from candidate order.
     */
    private List<Integer> validate(List<Integer> proposed, List<RerankCandidate> candidates, int limit) {
        Set<Integer> allowed = candidates.stream()
                .map(RerankCandidate::productId)
                .collect(Collectors.toSet());

        Set<Integer> picked = new LinkedHashSet<>();
        for (Integer id : proposed) {
            if (allowed.contains(id) && picked.size() < limit) {
                picked.add(id);
            } else if (!allowed.contains(id)) {
                log.warn("reranker returned id {} which is not a candidate - dropped", id);
            }
        }

        for (RerankCandidate candidate : candidates) {
            if (picked.size() >= limit) {
                break;
            }
            picked.add(candidate.productId());
        }
        return List.copyOf(picked);
    }
}
