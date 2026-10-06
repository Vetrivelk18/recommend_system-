package com.example.service;

import com.example.dto.RerankCandidate;
import com.example.repository.CandidateRepository;
import com.example.repository.CandidateRepository.CandidateRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gathers candidates from both arms, tags each with where it came from, and hands the
 * merged pool to the reranker.
 *
 * <p>The tagging is the point. A product both arms surfaced is a stronger signal than
 * one either found alone, and the reranker is told which is which - it cannot infer
 * that from a flat list. Provenance also lets the prompt warn that one arm
 * over-produces certain departments, so a long run of similar items reads as a
 * retrieval artefact rather than as a preference.
 */
@Service
public class CandidateService {

    private static final Logger log = LoggerFactory.getLogger(CandidateService.class);

    private static final int PER_ARM = 50;
    private static final int HISTORY_FOR_CONTEXT = 20;

    private final CandidateRepository candidates;
    private final ProductReranker reranker;

    public CandidateService(CandidateRepository candidates, ProductReranker reranker) {
        this.candidates = candidates;
        this.reranker = reranker;
    }

    /**
     * The whole flow for one user: gather, merge, rerank.
     *
     * <p>Returns empty when the user has no purchase history - both arms are centroid
     * queries over that history, so there is genuinely nothing to retrieve. Callers
     * must treat empty as "send nothing", not as a failure; a cold user needs the
     * onboarding preferences instead, which is a separate rail.
     */
    @Transactional(readOnly = true)
    public List<Integer> pickFor(Integer userId, int limit) {
        List<RerankCandidate> pool = gather(userId);
        if (pool.isEmpty()) {
            log.info("no candidates for user {} - no purchase history to build a centroid from", userId);
            return List.of();
        }

        List<String> bought = candidates.purchaseHistory(userId, HISTORY_FOR_CONTEXT);
        String context = bought.isEmpty() ? "No purchase history." : String.join(", ", bought);

        return reranker.rerank(context, pool, limit);
    }

    /**
     * Both arms, merged. A product found by both is upgraded to BOTH rather than
     * appearing twice - the reranker sees each product once, with the strongest
     * provenance it earned.
     */
    List<RerankCandidate> gather(Integer userId) {
        Map<Integer, RerankCandidate> merged = new LinkedHashMap<>();

        for (CandidateRow row : candidates.query(userId, PER_ARM)) {
            merged.put(row.getProductId(),
                    new RerankCandidate(row.getProductId(), row.getProduct(),
                            RerankCandidate.Source.QUERY));
        }

        for (CandidateRow row : candidates.behavioural(userId, PER_ARM)) {
            merged.merge(row.getProductId(),
                    new RerankCandidate(row.getProductId(), row.getProduct(),
                            RerankCandidate.Source.BEHAVIOURAL),
                    (existing, incoming) -> new RerankCandidate(
                            existing.productId(), existing.product(),
                            RerankCandidate.Source.BOTH));
        }

        List<RerankCandidate> pool = new ArrayList<>(merged.values());
        log.debug("user {}: {} candidates ({} from both arms)", userId, pool.size(),
                pool.stream().filter(c -> c.source() == RerankCandidate.Source.BOTH).count());
        return pool;
    }
}
