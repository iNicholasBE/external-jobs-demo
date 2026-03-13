package org.jobrunr.demo.gpu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Thin wrapper around the Replicate HTTP API.
 * Runs AI video generation on real GPUs — no simulation.
 */
@Service
public class ReplicateService {

    private static final String API_BASE = "https://api.replicate.com/v1";
    private static final String MODEL = "lightricks/ltx-2.3-fast";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper;
    private final String apiToken;

    public ReplicateService(ObjectMapper mapper,
                            @Value("${replicate.api-token}") String apiToken) {
        this.mapper = mapper;
        this.apiToken = apiToken;
    }

    public Prediction createPrediction(String prompt) {
        try {
            ObjectNode input = mapper.createObjectNode().put("prompt", prompt);
            ObjectNode body = mapper.createObjectNode().set("input", input);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/models/" + MODEL + "/predictions"))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new RuntimeException("Replicate API error (%d): %s".formatted(response.statusCode(), response.body()));
            }
            return parsePrediction(mapper.readTree(response.body()));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create Replicate prediction", e);
        }
    }

    public Prediction getPrediction(String predictionId) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + "/predictions/" + predictionId))
                    .header("Authorization", "Bearer " + apiToken)
                    .GET()
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parsePrediction(mapper.readTree(response.body()));
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get Replicate prediction", e);
        }
    }

    private Prediction parsePrediction(JsonNode json) {
        String id = json.path("id").asText();
        String status = json.path("status").asText();

        String output = null;
        JsonNode outputNode = json.path("output");
        if (outputNode.isArray() && !outputNode.isEmpty()) {
            output = outputNode.get(0).asText();
        } else if (outputNode.isTextual()) {
            output = outputNode.asText();
        }

        String error = json.path("error").isNull() ? null : json.path("error").asText();
        double predictTime = json.path("metrics").path("predict_time").asDouble(0);

        return new Prediction(id, status, output, error, predictTime);
    }

    public record Prediction(String id, String status, String output, String error, double predictTimeSeconds) {
        public boolean isTerminal() {
            return "succeeded".equals(status) || "failed".equals(status) || "canceled".equals(status);
        }

        public boolean succeeded() {
            return "succeeded".equals(status);
        }
    }
}
