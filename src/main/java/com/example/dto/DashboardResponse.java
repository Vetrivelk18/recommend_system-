package com.example.dto;

import java.util.List;

/**
 * Rails are product names only. A rail's list is empty when it does not apply to
 * this user - the client hides the section rather than showing an empty heading.
 *
 * forYouTitle is null when the user has no purchase history.
 */
public record DashboardResponse(
        Integer userId,
        String name,
        String forYouTitle,
        List<String> forYou,
        List<String> runningLow,
        List<String> popular) {
}
