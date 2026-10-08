package com.example.service;

import com.example.entity.Product;
import com.example.repository.CandidateRepository.CandidateRow;
import com.example.repository.ProductRepository;
import com.example.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The query box. Embeds the typed text once, runs RRF once, stores the full pool,
 * and returns only the slice worth displaying.
 *
 * <p>The pool computed and stored (STORE_SIZE) is deliberately larger than what any
 * caller displays (displayLimit): the same RRF run has to serve two different
 * consumers later - a person scrolling search results wants ~10, but the webhook
 * job 15 minutes after JWT expiry wants up to 50 to combine with the behavioural
 * arm. Storing the larger pool once means the webhook never re-embeds the query or
 * reruns RRF - it just reads what is already there.
 *
 * <p>Repeating a query skips both the embed hop and RRF via SearchCacheService, and
 * leaves the user's cached recommendations alone: the stored pool is byte-identical,
 * so there is nothing for the reranker to reconsider. Only a changed query evicts.
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private static final int CANDIDATES_PER_ARM = 100;
    private static final int STORE_SIZE = 50;

    private final EmbedClient embedClient;
    private final SearchRepository searchRepository;
    private final ProductRepository productRepository;
    private final SearchCacheService searchCache;
    private final RecommendationCacheService recommendationCache;

    public SearchService(EmbedClient embedClient,
                         SearchRepository searchRepository,
                         ProductRepository productRepository,
                         SearchCacheService searchCache,
                         RecommendationCacheService recommendationCache) {
        this.embedClient = embedClient;
        this.searchRepository = searchRepository;
        this.productRepository = productRepository;
        this.searchCache = searchCache;
        this.recommendationCache = recommendationCache;
    }

    /**
     * userId is optional: search works for a browsing session with no logged-in
     * user, it just has nothing to store for the webhook to reuse later.
     */
    @Transactional
    public List<CandidateRow> search(Integer userId, String queryText, int displayLimit) {
        Optional<List<Integer>> cached = searchCache.get(queryText);
        log.info("search cache {} for \"{}\"", cached.isPresent() ? "HIT" : "MISS", queryText);

        List<CandidateRow> pool = cached.map(this::rowsFor).orElseGet(() -> compute(queryText));

        if (userId != null && !pool.isEmpty()) {
            saveFor(userId, queryText, pool);
        }

        return pool.size() <= displayLimit ? pool : pool.subList(0, displayLimit);
    }

    /**
     * Embed, fuse, store. The embedding is the one external call on this path, and since
     * the embed service moved off-box it is also the one that can disappear on its own -
     * a free tier sleeps after idle and a cold start can outlast the read timeout.
     *
     * <p>So it degrades rather than fails. Every other external call in this project
     * already does: Redis treats an error as a miss, the reranker falls back to candidate
     * order. This was the exception - an embed failure propagated out as a 500 and the
     * search box simply broke - which was tolerable only while both processes lived and
     * died together.
     */
    private List<CandidateRow> compute(String queryText) {
        String vector;
        try {
            vector = embedClient.embed(queryText);
        } catch (Exception e) {
            // Not cached: these results are deliberately worse than a fused search, and
            // caching them would keep serving the degraded ranking for ten minutes after
            // the embed service came back.
            log.warn("embed failed for \"{}\" - falling back to lexical-only search: {}",
                    queryText, e.toString());
            return searchRepository.lexicalOnly(queryText, STORE_SIZE);
        }

        List<CandidateRow> pool =
                searchRepository.rrf(vector, queryText, CANDIDATES_PER_ARM, STORE_SIZE);

        if (!pool.isEmpty()) {
            searchCache.put(queryText, pool.stream().map(CandidateRow::getProductId).toList());
        }
        return pool;
    }

    /**
     * last_search is written on every search, hit or cached: searched_at is what makes
     * the row due, so skipping the write would stop the webhook from ever firing again.
     */
    private void saveFor(Integer userId, String queryText, List<CandidateRow> pool) {
        boolean queryChanged = searchRepository.currentQueryText(userId)
                .map(previous -> !normalise(previous).equals(normalise(queryText)))
                .orElse(true);

        String idsLiteral = pool.stream()
                .map(row -> String.valueOf(row.getProductId()))
                .collect(Collectors.joining(",", "{", "}"));
        searchRepository.saveLastSearch(userId, queryText, idsLiteral);

        if (queryChanged) {
            // The cached picks were derived from the search this row just replaced.
            recommendationCache.evict(userId);
        }
    }

    /** Product names for cached ids, back in the order RRF ranked them. */
    private List<CandidateRow> rowsFor(List<Integer> productIds) {
        Map<Integer, String> namesById = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getProductId, Product::getProduct));

        return productIds.stream()
                .filter(namesById::containsKey)
                .map(id -> (CandidateRow) new CachedRow(id, namesById.get(id)))
                .toList();
    }

    private String normalise(String queryText) {
        return queryText.trim().toLowerCase(Locale.ROOT);
    }

    /** CandidateRow is a projection interface, so a cached pool needs its own carrier. */
    private record CachedRow(Integer productId, String product) implements CandidateRow {
        @Override
        public Integer getProductId() {
            return productId;
        }

        @Override
        public String getProduct() {
            return product;
        }
    }
}
