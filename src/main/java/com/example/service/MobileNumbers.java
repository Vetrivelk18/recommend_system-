package com.example.service;

/**
 * users.mobile is bare digits (see SignupRequest's \d{10,15} validation) with no
 * country code on file. Shared by every WebhookSender implementation so the default
 * stays consistent regardless of which provider is active.
 */
final class MobileNumbers {

    private static final String DEFAULT_COUNTRY_CODE = "+91"; // India

    private MobileNumbers() {
    }

    /** Already-E.164 input (a leading '+') passes through unchanged. */
    static String toE164(String mobile) {
        if (mobile == null || mobile.isBlank() || mobile.startsWith("+")) {
            return mobile;
        }
        return DEFAULT_COUNTRY_CODE + mobile;
    }
}
