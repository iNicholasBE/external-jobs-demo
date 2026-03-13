package org.jobrunr.demo.approval;

import org.jobrunr.jobs.JobId;
import org.jobrunr.scheduling.BackgroundJob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.StreamSupport;

import static org.jobrunr.scheduling.JobBuilder.anExternalJob;

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

    private final ApprovalRepository repository;

    public AiApprovalService(ApprovalRepository repository) {
        this.repository = repository;
    }

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
        String jobKey = "review-" + UUID.randomUUID();

        var request = new ApprovalRequest();
        request.setJobKey(jobKey);
        request.setProductName(productName);
        request.setStatus("ANALYZING");
        request.setCreatedAt(LocalDateTime.now());
        request = repository.save(request);

        Long requestId = request.getId();

        BackgroundJob.create(anExternalJob()
                .withId(JobId.fromIdentifier(jobKey))
                .withName("AI Content Review: " + productName)
                .withLabels("ai-review", productName)
                .withQueue("high-prio")
                .withAmountOfRetries(0)
                .withDetails(() -> analyzeContent(requestId)));
    }

    /** Called by JobRunr — simulates AI generating marketing copy and a confidence score. */
    @Transactional
    public void analyzeContent(Long requestId) {
        var request = repository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("Approval request not found: " + requestId));

        var rng = ThreadLocalRandom.current();
        String content = TEMPLATES.get(rng.nextInt(TEMPLATES.size())).formatted(request.getProductName());
        double confidence = 0.65 + rng.nextDouble() * 0.30;

        request.setContent(content);
        request.setAiConfidence(Math.round(confidence * 100.0) / 100.0);
        request.setAiRecommendation(confidence > 0.85 ? "PUBLISH" : "NEEDS_REVIEW");
        request.setStatus("PENDING_REVIEW");
        repository.save(request);
    }

    @Transactional
    public void approve(Long requestId) {
        var request = repository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("Approval request not found: " + requestId));

        BackgroundJob.signalExternalJobSucceeded(
                JobId.fromIdentifier(request.getJobKey()),
                "Content approved by human reviewer");

        request.setStatus("APPROVED");
        repository.save(request);
    }

    @Transactional
    public void decline(Long requestId) {
        var request = repository.findById(requestId)
                .orElseThrow(() -> new IllegalStateException("Approval request not found: " + requestId));

        BackgroundJob.signalExternalJobFailed(
                JobId.fromIdentifier(request.getJobKey()),
                "Content declined by human reviewer");

        request.setStatus("DECLINED");
        repository.save(request);
    }

    public List<ApprovalRequest> getAnalyzingRequests() {
        return allByStatus("ANALYZING");
    }

    public List<ApprovalRequest> getPendingRequests() {
        return allByStatus("PENDING_REVIEW");
    }

    public List<ApprovalRequest> getCompletedRequests() {
        return StreamSupport.stream(repository.findAll().spliterator(), false)
                .filter(r -> "APPROVED".equals(r.getStatus()) || "DECLINED".equals(r.getStatus()))
                .sorted(Comparator.comparing(ApprovalRequest::getCreatedAt).reversed())
                .toList();
    }

    private List<ApprovalRequest> allByStatus(String status) {
        return StreamSupport.stream(repository.findAll().spliterator(), false)
                .filter(r -> status.equals(r.getStatus()))
                .sorted(Comparator.comparing(ApprovalRequest::getCreatedAt).reversed())
                .toList();
    }
}
