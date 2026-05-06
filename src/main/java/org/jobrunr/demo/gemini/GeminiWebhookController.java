package org.jobrunr.demo.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GeminiWebhookController {

    private final GeminiWebhookSetup setup;
    private final GeminiBatchService batchService;
    private final GeminiVideoService videoService;
    private final ObjectMapper mapper;

    public GeminiWebhookController(GeminiWebhookSetup setup,
                                   GeminiBatchService batchService,
                                   GeminiVideoService videoService,
                                   ObjectMapper mapper) {
        this.setup = setup;
        this.batchService = batchService;
        this.videoService = videoService;
        this.mapper = mapper;
    }

    @PostMapping(value = "${gemini.webhook.path}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(
            @RequestHeader(value = "webhook-id", required = false) String webhookId,
            @RequestHeader(value = "webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "webhook-signature", required = false) String signature,
            @RequestBody String body) {

        if (!setup.isReady()) {
            System.err.println("[gemini] received webhook but setup is not ready");
            return ResponseEntity.status(503).body("webhook not configured");
        }

        try {
            setup.verifier().verify(webhookId, timestamp, signature, body);
        } catch (SecurityException e) {
            System.err.println("[gemini] signature verification failed: " + e.getMessage());
            return ResponseEntity.status(400).body("invalid signature");
        }

        try {
            JsonNode event = mapper.readTree(body);
            String type = event.path("type").asText();
            JsonNode data = event.path("data");
            String id = data.path("id").asText();

            System.out.println("[gemini] webhook " + type + " for batch " + id);

            switch (type) {
                case "batch.succeeded" -> batchService.onBatchSucceeded(id);
                case "batch.failed" -> batchService.onBatchFailed(id, data.path("error_message").asText("failed"));
                case "batch.expired" -> batchService.onBatchFailed(id, "expired");
                case "video.generated" -> videoService.onVideoGenerated(
                        id,
                        data.path("output_file_uri").asText(
                                data.path("file_name").asText(null)));
                case "video.failed" -> videoService.onVideoFailed(id, data.path("error_message").asText("failed"));
                default -> System.out.println("[gemini] ignoring event type " + type + " data=" + data);
            }
            return ResponseEntity.ok("{\"status\":\"received\"}");
        } catch (Exception e) {
            System.err.println("[gemini] failed to handle webhook: " + e.getMessage());
            // Returning 500 makes Gemini retry. For demo purposes that's fine.
            return ResponseEntity.status(500).body("error");
        }
    }
}
