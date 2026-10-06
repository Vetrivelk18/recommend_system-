package com.example.dto;

/**
 * One product put in front of the reranker.
 *
 * <p>{@code source} is carried through to the prompt on purpose. The two candidate
 * rails mean different things - the query rail says "this matches what they searched",
 * the behavioural rail says "users like this bought it" - and the behavioural rail
 * currently concentrates most of its output into a handful of departments. A model
 * told where each candidate came from can discount that; one handed an unlabelled
 * list reads the concentration as a genuine signal.
 */
public record RerankCandidate(int productId, String product, Source source) {

    public enum Source { QUERY, BEHAVIOURAL, BOTH }
}
