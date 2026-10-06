package com.example.controller;

import com.example.entity.Product;
import com.example.repository.ProductRepository;
import com.example.service.RecommendationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * On-demand read of the same picks the webhook sends, off the same cache. No limit
 * parameter: the size is fixed by RecommendationService, because one cache key cannot
 * honestly answer requests for different sizes.
 *
 * <p>Empty for a user with no purchase history, and for an unknown id - both mean "nothing
 * to recommend" rather than an error, so neither is a 404.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final ProductRepository productRepository;

    public RecommendationController(RecommendationService recommendationService,
                                    ProductRepository productRepository) {
        this.recommendationService = recommendationService;
        this.productRepository = productRepository;
    }

    @GetMapping("/{userId}")
    public List<String> recommendations(@PathVariable Integer userId) {
        List<Integer> picks = recommendationService.topFor(userId);

        // findAllById returns rows in whatever order the database hands back, so the rank
        // is reapplied here rather than trusting it.
        Map<Integer, String> namesById = productRepository.findAllById(picks).stream()
                .collect(Collectors.toMap(Product::getProductId, Product::getProduct));

        return picks.stream()
                .map(namesById::get)
                .filter(Objects::nonNull)
                .toList();
    }
}
