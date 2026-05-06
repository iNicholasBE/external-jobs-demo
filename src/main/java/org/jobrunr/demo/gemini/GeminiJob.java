package org.jobrunr.demo.gemini;

import java.time.Instant;
import java.util.UUID;

public record GeminiJob(
        UUID jobKey,
        String prompt,
        String batchName,
        String status,
        String result,
        String error,
        Instant createdAt) {

    public GeminiJob withBatchName(String name) {
        return new GeminiJob(jobKey, prompt, name, "submitted", result, error, createdAt);
    }

    public GeminiJob withResult(String result) {
        return new GeminiJob(jobKey, prompt, batchName, "succeeded", result, null, createdAt);
    }

    public GeminiJob withError(String error) {
        return new GeminiJob(jobKey, prompt, batchName, "failed", null, error, createdAt);
    }
}
