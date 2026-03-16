package org.jobrunr.demo.gpu;

import org.jobrunr.scheduling.BackgroundJob;
import org.jobrunr.server.runner.ThreadLocalJobContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.jobrunr.scheduling.JobBuilder.anExternalJob;

/**
 * GPU video generation via Replicate, tracked as External Jobs in JobRunr.
 *
 * Flow:
 *   1. User submits a prompt > External Job is created
 *   2. The job trigger calls the Replicate API to start a GPU prediction
 *   3. The job enters PROCESSED state, waiting for the GPU to finish
 *   4. A poller detects completion and signals the External Job
 */
@Service
public class GpuJobService {

    private final ReplicateService replicate;
    private final Map<UUID, GpuJob> activeJobs = new ConcurrentHashMap<>();
    private final List<GpuJob> completedJobs = new ArrayList<>();

    public GpuJobService(ReplicateService replicate) {
        this.replicate = replicate;
    }

    public GpuJob launch(String prompt) {
        var jobId = BackgroundJob.create(anExternalJob()
                .withName("GPU Video: " + truncate(prompt, 60))
                .withLabels("gpu", "replicate")
                .withQueue("high-prio")
                .withDetails(() -> triggerPrediction(prompt)));

        UUID jobKey = jobId.asUUID();
        var job = new GpuJob(jobKey, prompt, null, "queued", null, 0, Instant.now());
        activeJobs.put(jobKey, job);
        return job;
    }

    /** Called by JobRunr when the External Job is picked up by a worker. */
    public void triggerPrediction(String prompt) {
        var jobContext = ThreadLocalJobContext.getJobContext();
        UUID jobKey = jobContext.getJobId();
        var prediction = replicate.createPrediction(prompt);
        activeJobs.put(jobKey, new GpuJob(jobKey, prompt, prediction.id(), prediction.status(), null, 0, Instant.now()));
        ensurePollerRunning();
    }

    private void ensurePollerRunning() {
        BackgroundJob.scheduleRecurrently("gpu-poller", Duration.ofSeconds(5), () -> pollPredictions());
    }

    /** Polls Replicate every 5s and signals External Jobs on completion. */
    public void pollPredictions() {
        for (var entry : activeJobs.entrySet()) {
            UUID jobKey = entry.getKey();
            GpuJob job = entry.getValue();
            if (job.predictionId() == null) continue;

            try {
                var prediction = replicate.getPrediction(job.predictionId());

                if (prediction.succeeded()) {
                    BackgroundJob.signalExternalJobSucceeded(
                            jobKey,
                            "Video generated in %.1fs".formatted(prediction.predictTimeSeconds()));
                    completedJobs.addFirst(job.withResult("succeeded", prediction.output(), prediction.predictTimeSeconds()));
                    activeJobs.remove(jobKey);
                    stopPollerIfIdle();

                } else if (prediction.isTerminal()) {
                    BackgroundJob.signalExternalJobFailed(
                            jobKey,
                            "Prediction failed: " + prediction.error());
                    activeJobs.remove(jobKey);
                    stopPollerIfIdle();

                } else {
                    activeJobs.put(jobKey, job.withStatus(prediction.status()));
                }
            } catch (Exception e) {
                System.err.printf("Error polling prediction %s: %s%n", jobKey, e.getMessage());
            }
        }
    }

    private void stopPollerIfIdle() {
        if (activeJobs.isEmpty()) {
            BackgroundJob.deleteRecurringJob("gpu-poller");
        }
    }

    public Collection<GpuJob> getActiveJobs() { return activeJobs.values(); }
    public List<GpuJob> getCompletedJobs() { return completedJobs; }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
