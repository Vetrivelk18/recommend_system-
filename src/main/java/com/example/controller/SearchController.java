package com.example.controller;

import com.example.repository.CandidateRepository.CandidateRow;
import com.example.service.RabbitMQProducer;
import com.example.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final RabbitMQProducer rabbitMQProducer;

    public SearchController(SearchService searchService,
                            RabbitMQProducer rabbitMQProducer) {
        this.searchService = searchService;
        this.rabbitMQProducer = rabbitMQProducer;
    }

    /**
     * userId is optional - an anonymous search still returns results, it just has
     * nothing to hand the webhook job later. When present, this call is also what
     * populates last_search for that user.
     */
    @GetMapping
    public List<CandidateRow> search(@RequestParam String q,
                                     @RequestParam(required = false) Integer userId,
                                     @RequestParam(defaultValue = "10") int limit) {
        List<CandidateRow> results = searchService.search(userId, q, limit);

        // Published here rather than inside SearchService, and after search() returns, so
        // its transaction has committed: the consumer reads the last_search row this call
        // just wrote rather than the one it replaced. Publishing from inside the
        // transaction would let the consumer win the race and rerank the previous query.
        if (userId != null) {
            rabbitMQProducer.sendRecommendationJob(userId.longValue(), q);
        }
        return results;
    }
}
