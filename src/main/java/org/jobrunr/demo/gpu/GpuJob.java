package org.jobrunr.demo.gpu;

import java.time.Instant;
import java.util.UUID;

public record GpuJob(
        UUID jobKey,
        String prompt,
        String predictionId,
        String status,
        String outputUrl,
        double predictTimeSeconds,
        Instant createdAt) {

    public GpuJob withStatus(String newStatus) {
        return new GpuJob(jobKey, prompt, predictionId, newStatus, outputUrl, predictTimeSeconds, createdAt);
    }

    public GpuJob withResult(String newStatus, String outputUrl, double predictTime) {
        return new GpuJob(jobKey, prompt, predictionId, newStatus, outputUrl, predictTime, createdAt);
    }
}
