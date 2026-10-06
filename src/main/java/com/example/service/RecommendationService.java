package com.example.service;

import com.example.repository.SearchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cached read of a user's picks, shared by every caller - the scheduled WebhookJob,
 * the on-demand RecommendationController, and RabbitMQConsumer alike.
 *
 * <p>The search-time warm that used to live here as an @Async method is now a queued job:
 * a search publishes a RecommendationJob and the consumer calls topFor synchronously, so
 * the broker only acks once the rerank has genuinely finished. What topFor does - the
 * cache-aside, the single-flight dedup, the discard-if-they-searched-again check - is
 * unchanged; only the dispatch moved.
 *
 * <p>Cache-aside lives here rather than in either caller so the two cannot drift: one
 * key, one TTL, one definition of what a hit means. The limit is owned here for the same
 * reason - a caller passing its own would read a five-item entry expecting ten.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final CandidateService candidateService;
    private final RecommendationCacheService cache;
    private final SearchRepository searchRepository;
    private final int limit;

    /**
     * Reranks currently running, so a second caller for the same input waits on the first
     * instead of starting its own 14B model call. Keyed by the query as well as the user:
     * a caller whose user has searched since must not be handed picks for the old query.
     * In-memory, so it only deduplicates within one running instance.
     */
    private final ConcurrentHashMap<RerankKey, CompletableFuture<List<Integer>>> inFlight =
            new ConcurrentHashMap<>();

    public RecommendationService(CandidateService candidateService,
                                 RecommendationCacheService cache,
                                 SearchRepository searchRepository,
                                 @Value("${webhook.limit}") int limit) {
        this.candidateService = candidateService;
        this.cache = cache;
        this.searchRepository = searchRepository;
        this.limit = limit;
    }

    /** Empty when the user has no purchase history - see CandidateService.pickFor. */
    public List<Integer> topFor(Integer userId) {
        Optional<List<Integer>> cached = cache.get(userId);
        // Logged before the compute, not after: on a miss the rerank below is the slow
        // part, and a line that only prints once it finishes cannot explain the wait.
        log.info("recommendation cache {} for user {}", cached.isPresent() ? "HIT" : "MISS", userId);

        return cached.orElseGet(() -> rerankOnce(userId));
    }

    private List<Integer> rerankOnce(Integer userId) {
        RerankKey key = new RerankKey(userId, currentQuery(userId));
        CompletableFuture<List<Integer>> mine = new CompletableFuture<>();
        CompletableFuture<List<Integer>> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            log.info("joining in-flight rerank for user {}", userId);
            return await(running);
        }

        try {
            List<Integer> picks = candidateService.pickFor(userId, limit);
            if (!picks.isEmpty()) {
                // Check, put, check again. The first check stops a rerank for an old query
                // overwriting fresher picks another rerank already cached. The second catches
                // a search landing between that check and the put: a search saves last_search
                // before it evicts, so the re-read always sees it.
                if (isStale(key)) {
                    log.info("discarding rerank for user {} - searched again while it ran", userId);
                } else {
                    cache.put(userId, picks);
                    if (isStale(key)) {
                        log.info("discarding rerank for user {} - searched again while it ran", userId);
                        cache.evict(userId);
                    }
                }
            }
            mine.complete(picks);
            return picks;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private boolean isStale(RerankKey key) {
        return !Objects.equals(key.query(), currentQuery(key.userId()));
    }

    /** Same trim-and-lowercase as SearchService, so a case-only change is one input, not two. */
    private String currentQuery(Integer userId) {
        return searchRepository.currentQueryText(userId)
                .map(q -> q.trim().toLowerCase(Locale.ROOT))
                .orElse(null);
    }

    private static List<Integer> await(CompletableFuture<List<Integer>> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private record RerankKey(Integer userId, String query) {
    }
}
