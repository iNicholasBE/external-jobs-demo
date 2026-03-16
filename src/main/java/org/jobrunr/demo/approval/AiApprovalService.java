package org.jobrunr.demo.approval;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.context.JobContext;
import org.jobrunr.jobs.states.StateName;
import org.jobrunr.scheduling.BackgroundJob;
import org.jobrunr.storage.Paging;
import org.jobrunr.storage.StorageProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

import static org.jobrunr.scheduling.JobBuilder.anExternalJob;
import static org.jobrunr.storage.JobSearchRequestBuilder.aJobSearchRequest;

/**
 * Human-in-the-loop AI content approval, powered by External Jobs.
 *
 * Flow:
 *   1. User requests AI-generated marketing copy for a product
 *   2. An External Job is created — the AI generates content + confidence score
 *   3. The job enters PROCESSED state, waiting for human review
 *   4. A human approves or declines → signals the External Job
 */
@Service
public class AiApprovalService {

    private final StorageProvider storageProvider;
    private final List<ReviewItem> completedReviews = new CopyOnWriteArrayList<>();

    public AiApprovalService(StorageProvider storageProvider) {
        this.storageProvider = storageProvider;
    }

    public record ReviewItem(UUID jobId, String productName, String content,
                              int confidencePercent, String recommendation, String status) {}

    private static final List<String> PRODUCTS = List.of(
            "CloudSync Pro", "AI Code Buddy", "DataFlow Pipeline",
            "SecureVault Enterprise", "MetricsMaster", "DeployBot",
            "APIForge", "LogStream Analytics", "ContainerPilot",
            "ModelHub", "InfraScope", "QueryTurbo");

    private static final List<String> TEMPLATES = List.of(
            "Transform your workflow with %s — the next generation platform that delivers " +
                    "unparalleled performance, seamless integration, and enterprise-grade reliability. " +
                    "Built for teams that demand excellence.",
            "Introducing %s: where innovation meets simplicity. Our cutting-edge solution empowers " +
                    "developers to build, deploy, and scale with confidence. Join 10,000+ teams " +
                    "already shipping faster.",
            "Meet %s — your secret weapon for 10x productivity. Powered by advanced AI, " +
                    "it anticipates your needs and automates the mundane so you can focus " +
                    "on what truly matters.",
            "%s redefines what's possible. With real-time collaboration, intelligent automation, " +
                    "and deep analytics, your team will ship faster and with fewer bugs than ever before.",
            "Why the best engineering teams choose %s: battle-tested infrastructure, zero-downtime " +
                    "deployments, and observability built in from day one. See the difference in your " +
                    "first sprint.");

    public String randomProductName() {
        return PRODUCTS.get(ThreadLocalRandom.current().nextInt(PRODUCTS.size()));
    }

    public void createReviewRequest(String productName) {
        BackgroundJob.create(anExternalJob()
                .withName("AI Content Review: " + productName)
                .withLabels("ai-review", productName)
                .withQueue("high-prio")
                .withAmountOfRetries(0)
                .withDetails(() -> analyzeContent(productName, JobContext.Null)));
    }

    /** Called by JobRunr — simulates AI generating marketing copy and a confidence score. */
    public void analyzeContent(String productName, JobContext jobContext) {
        var rng = ThreadLocalRandom.current();
        String content = TEMPLATES.get(rng.nextInt(TEMPLATES.size())).formatted(productName);
        double confidence = Math.round((0.65 + rng.nextDouble() * 0.30) * 100.0) / 100.0;
        String recommendation = confidence > 0.85 ? "PUBLISH" : "NEEDS_REVIEW";

        jobContext.saveMetadata("content", content);
        jobContext.saveMetadata("aiConfidence", String.valueOf(confidence));
        jobContext.saveMetadata("aiRecommendation", recommendation);
    }

    public void approve(UUID jobId) {
        Job job = storageProvider.getJobById(jobId);
        cacheCompleted(job, "APPROVED");
        BackgroundJob.signalExternalJobSucceeded(jobId, "Content approved by human reviewer");
    }

    public void decline(UUID jobId) {
        Job job = storageProvider.getJobById(jobId);
        cacheCompleted(job, "DECLINED");
        BackgroundJob.signalExternalJobFailed(jobId, "Content declined by human reviewer");
    }

    public List<ReviewItem> getAnalyzingReviews() {
        var request = aJobSearchRequest(StateName.PROCESSING).withLabel("ai-review").build();
        return storageProvider.getJobList(request, Paging.AmountBasedList.descOnUpdatedAt(50))
                .stream()
                .map(job -> new ReviewItem(job.getId(), extractProductName(job), null, 0, null, "ANALYZING"))
                .toList();
    }

    public List<ReviewItem> getPendingReviews() {
        var request = aJobSearchRequest(StateName.PROCESSED).withLabel("ai-review").build();
        return storageProvider.getJobList(request, Paging.AmountBasedList.descOnUpdatedAt(50))
                .stream()
                .map(job -> {
                    String content = (String) job.getMetadata().get("content");
                    double conf = Double.parseDouble((String) job.getMetadata().get("aiConfidence"));
                    String rec = (String) job.getMetadata().get("aiRecommendation");
                    return new ReviewItem(job.getId(), extractProductName(job), content, (int) (conf * 100), rec, "PENDING_REVIEW");
                })
                .toList();
    }

    public List<ReviewItem> getCompletedReviews() {
        return completedReviews;
    }

    private void cacheCompleted(Job job, String status) {
        String content = (String) job.getMetadata().get("content");
        double confidence = Double.parseDouble((String) job.getMetadata().get("aiConfidence"));
        String recommendation = (String) job.getMetadata().get("aiRecommendation");
        completedReviews.addFirst(new ReviewItem(
                job.getId(), extractProductName(job), content, (int) (confidence * 100), recommendation, status));
    }

    private static String extractProductName(Job job) {
        return job.getLabels().size() > 1 ? job.getLabels().get(1) : job.getJobName();
    }
}
