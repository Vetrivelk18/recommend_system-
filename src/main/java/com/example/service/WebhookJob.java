package com.example.service;

import com.example.entity.Product;
import com.example.entity.User;
import com.example.repository.ProductRepository;
import com.example.repository.SearchRepository;
import com.example.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls last_search for rows old enough to notify on (webhook.delay-minutes after
 * searched_at - see SearchRepository.dueForWebhook), asks RecommendationService for each
 * due user's picks, and texts them via WebhookSender.
 *
 * <p>Whether those picks came from the cache or from a fresh rerank is RecommendationService's
 * concern, not this job's. Eligibility comes from searched_at/webhook_sent_at alone - the
 * cache changes what a due user costs, never who is due.
 *
 * <p>One user's failure (bad reranker call, missing mobile, provider error) must not
 * stop the rest of the batch - each is caught and logged, left for the next poll to
 * retry, same spirit as OllamaReranker's fallback.
 */
@Component
public class WebhookJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookJob.class);

    private final SearchRepository searchRepository;
    private final RecommendationService recommendationService;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final WebhookSender sender;
    private final int delayMinutes;

    public WebhookJob(SearchRepository searchRepository,
                       RecommendationService recommendationService,
                       ProductRepository productRepository,
                       UserRepository userRepository,
                       WebhookSender sender,
                       @Value("${webhook.delay-minutes}") int delayMinutes) {
        this.searchRepository = searchRepository;
        this.recommendationService = recommendationService;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.sender = sender;
        this.delayMinutes = delayMinutes;
    }

    @Scheduled(fixedDelayString = "${webhook.poll-interval}")
    public void run() {
        List<Integer> due = searchRepository.dueForWebhook(delayMinutes);
        for (Integer userId : due) {
            try {
                process(userId);
            } catch (Exception e) {
                log.warn("webhook failed for user {} - left for the next poll", userId, e);
            }
        }
    }

    private void process(Integer userId) {
        List<Integer> picks = recommendationService.topFor(userId);
        if (picks.isEmpty()) {
            // No purchase history to build a centroid from (see CandidateService) -
            // nothing to send, and nothing will change before the next search, so
            // stop this row from being picked up again every poll.
            searchRepository.markWebhookSent(userId);
            return;
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getMobile() == null || user.getMobile().isBlank()) {
            log.warn("user {} due for webhook but has no mobile on file - skipping", userId);
            searchRepository.markWebhookSent(userId);
            return;
        }

        List<String> names = productRepository.findAllById(picks).stream()
                .map(Product::getProduct)
                .toList();

        sender.send(user.getMobile(), names);
        searchRepository.markWebhookSent(userId);
    }
}
