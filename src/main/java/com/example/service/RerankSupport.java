package com.example.service;

import com.example.dto.RerankCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The parts of reranking that are the same whichever model does it: the prompt, the way
 * candidates are rendered, the JSON schema the reply must satisfy, and the validation of
 * what comes back.
 *
 * <p>Extracted when the Gemini implementation arrived. Only the HTTP call and the shape of
 * the provider's response differ between the two; duplicating the rest would mean tuning
 * the prompt in one place and quietly not in the other.
 */
final class RerankSupport {

    private static final Logger log = LoggerFactory.getLogger(RerankSupport.class);

    static final String SYSTEM = """
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

    private RerankSupport() {
    }

    /** One candidate per line: id, name, and which rail found it. */
    static String candidateList(List<RerankCandidate> candidates) {
        return candidates.stream()
                .map(c -> "%d\t%s\t[%s]".formatted(c.productId(), c.product(), c.source()))
                .collect(Collectors.joining("\n"));
    }

    static String userPrompt(String userContext, List<RerankCandidate> candidates, int limit) {
        return "Shopper:\n%s\n\nCandidates (id, product, source):\n%s\n\nPick %d."
                .formatted(userContext, candidateList(candidates), limit);
    }

    /**
     * The object both providers are told to produce. Ollama takes it as "format" and Gemini
     * as generationConfig.responseSchema; the vocabulary is the same OpenAPI subset, so one
     * definition serves both.
     *
     * <p>It constrains the SHAPE of the reply, never its truth - a model can still return a
     * well-formed id that is not in the candidate set. That is what validate is for.
     */
    static Map<String, Object> responseSchema(int limit) {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "product_ids", Map.of(
                                "type", "array",
                                "items", Map.of("type", "integer"),
                                "minItems", limit,
                                "maxItems", limit)),
                "required", List.of("product_ids"));
    }

    /**
     * The schema guarantees shape, not truth - a model can return a well-formed id that
     * is not in the candidate set, or repeat one. Both are silent failures downstream,
     * so unknown ids are dropped and any shortfall is topped up from candidate order.
     */
    static List<Integer> validate(List<Integer> proposed, List<RerankCandidate> candidates, int limit) {
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

    /**
     * What to send when the model could not be reached or could not be understood. The
     * candidate lists arrive already ranked, so this degrades quality rather than
     * correctness - a worse message beats no message.
     */
    static List<Integer> fallback(List<RerankCandidate> candidates, int limit) {
        return candidates.stream().map(RerankCandidate::productId).limit(limit).toList();
    }

    /** Nothing to choose between: fewer candidates than slots, or none at all. */
    static boolean nothingToDecide(List<RerankCandidate> candidates, int limit) {
        return candidates.size() <= limit;
    }
}
