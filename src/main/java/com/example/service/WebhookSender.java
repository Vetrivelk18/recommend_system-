package com.example.service;

import java.util.List;

/**
 * Where the webhook job's picks actually go. Kept separate from WebhookJob so a real
 * provider (Twilio, etc.) can be swapped in later as a new implementation - the job,
 * the candidate gathering, and the last_search polling are unaffected either way.
 */
public interface WebhookSender {

    void send(String mobile, List<String> productNames);
}
