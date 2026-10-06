package com.example.service;

import com.example.dto.DashboardResponse;
import com.example.entity.User;
import com.example.repository.ProductRepository;
import com.example.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Three rails:
 *   Running Low     - overdue relative to each item's own repurchase cycle
 *   Buy It Again    - this user's own history, from user_products
 *   Popular Now     - global ranking by distinct buyers
 *
 * The first two are history-based, so both are empty for anyone without order
 * history: every real signup, and the dataset users the source filters dropped.
 * The onboarding survey plays no part here - it would only matter for a rail that
 * personalises without history.
 */
@Service
public class DashboardService {

    private static final int RAIL_SIZE = 5;

    private final UserRepository userRepository;
    private final ProductRepository productRepository;

    public DashboardService(UserRepository userRepository,
                            ProductRepository productRepository) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse forUser(Integer userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnknownUserException(userId));

        List<String> popular = productRepository.topOverall(RAIL_SIZE);
        List<String> buyItAgain = productRepository.buyItAgain(userId, RAIL_SIZE);
        List<String> runningLow = productRepository.runningLow(userId, RAIL_SIZE);

        return new DashboardResponse(
                user.getUserId(),
                user.getName(),
                buyItAgain.isEmpty() ? null : "Buy it again",
                buyItAgain,
                runningLow,
                popular);
    }

    public static class UnknownUserException extends RuntimeException {
        public UnknownUserException(Integer userId) {
            super("no such user: " + userId);
        }
    }
}
