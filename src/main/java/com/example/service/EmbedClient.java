package com.example.service;

import tools.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Turns search-box text into a pgvector literal by calling ml/embed_service.py.
 *
 * <p>Java cannot run BGE itself, so this is the one network hop that exists purely
 * because the embedding model is Python. Everything downstream of this class is
 * plain SQL again.
 */
@Service
public class EmbedClient {

    private final RestClient client;

    public EmbedClient(RestClient embedRestClient) {
        this.client = embedRestClient;
    }

    /**
     * Returns a string already shaped for {@code CAST(:vec AS vector)} - e.g.
     * "[0.0123,-0.0456,...]" - rather than a float array, because that is the only
     * form every caller actually needs, and it matches how ml/*.py builds the same
     * literal for the same reason.
     */
    public String embed(String text) {
        JsonNode response = client.post()
                .uri("/embed")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", text))
                .retrieve()
                .body(JsonNode.class);

        JsonNode vector = response.path("vector");
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            if (i > 0) literal.append(',');
            literal.append(vector.get(i).asDouble());
        }
        return literal.append(']').toString();
    }
}
