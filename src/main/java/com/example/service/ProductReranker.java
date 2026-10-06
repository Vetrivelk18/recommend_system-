package com.example.service;

import com.example.dto.RerankCandidate;

import java.util.List;

/**
 * Picks the handful of products actually worth sending from a larger candidate pool.
 *
 * <p>The interface exists so the model behind it stays swappable - a local Ollama
 * model today, a hosted API if volume or quality demands it - without any caller
 * changing. Implementations must return ids drawn from {@code candidates} only, in
 * preference order, never more than {@code limit}.
 */
public interface ProductReranker {

    List<Integer> rerank(String userContext, List<RerankCandidate> candidates, int limit);
}
