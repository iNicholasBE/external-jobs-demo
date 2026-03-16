# External Jobs Demo: Project Guide

## What is this?
A Spring Boot demo app showcasing **JobRunr Pro 8.5 External Jobs**. These are jobs that are triggered by JobRunr but completed by an external system or a human decision.

## Tech stack
- Java 21, Spring Boot 3.5.6, Thymeleaf
- JobRunr Pro 8.5.0 (private Maven repo, credentials in `gradle.properties`)
- PostgreSQL 17 (via `docker-compose.yml`)
- Replicate API for real GPU inference (model: `lightricks/ltx-2.3-fast`)

## Running
```bash
docker compose up -d
export REPLICATE_API_TOKEN=r8_your_token
./gradlew bootRun
```
App: http://localhost:8080 | Dashboard: http://localhost:8080/dashboard

## Key External Jobs API
```java
// Create an External Job
BackgroundJob.create(anExternalJob()
    .withName("AI Content Review: " + productName)
    .withLabels("ai-review", productName)
    .withQueue("high-prio")
    .withDetails(() -> analyzeContent(productName, JobContext.Null)));

// Signal completion from outside
BackgroundJob.signalExternalJobSucceeded(jobId, "message");
BackgroundJob.signalExternalJobFailed(jobId, "reason");
```

## Architecture notes
- GPU jobs use an in-memory `ConcurrentHashMap` to track active predictions. This means active jobs are lost on restart (completed jobs in Replicate are not re-linked). This is fine for a demo.
- The poller in `GpuJobService` checks Replicate every 5s via a recurring job. In production, you'd use webhooks to avoid polling.
- The approval flow uses **JobRunr as the sole source of truth**, with no separate database table. AI-generated content is stored as job metadata via `JobContext.saveMetadata()`, and the dashboard queries `StorageProvider` for PROCESSED jobs with the `ai-review` label. Completed reviews are cached in-memory (lost on restart).

---

# Demo Script & Talking Points

## Opening (the problem)
> "In the real world, not every job completes inside your JVM. Some jobs depend on external systems: a GPU finishing an AI inference, a human approving content, a payment provider confirming a transaction. How do you track these in your job scheduler?"

## Scenario 1: GPU Video Generation (~2 min)

### Setup
1. Open http://localhost:8080/gpu
2. Open the JobRunr dashboard in a second tab: http://localhost:8080/dashboard

### Demo flow
1. **Submit a prompt**, e.g. "A cat astronaut floating through a colorful nebula"
2. **Show the dashboard**. The job appears as an External Job, state goes to PROCESSED
3. **Explain**: "JobRunr created the job, called our trigger which hit the Replicate API, and now the job is in PROCESSED state. It's not consuming any worker threads, it's just waiting."
4. **Wait ~18 seconds**. The poller detects completion and signals the job
5. **Show the video**. It appears on the page with GPU timing
6. **Show the dashboard**. Job moved to SUCCEEDED

### Key talking points
- **No worker threads wasted.** The job is parked in PROCESSED, not blocking a thread
- **Real GPU.** This isn't a simulation, it's running on Replicate's infrastructure
- **Decoupled.** The trigger just starts the work, a separate poller handles completion
- **In production, you'd use webhooks** instead of polling

## Scenario 2: Human-in-the-Loop Approval (~2 min)

### Setup
1. Open http://localhost:8080/approvals

### Demo flow
1. **Click "Generate AI Content"**. An External Job is created
2. **Show the dashboard**. Job is PROCESSING (AI is generating copy)
3. **Wait 1-2s**. AI finishes, content appears with confidence score and recommendation
4. **Show the dashboard**. Job is now in PROCESSED state, waiting for human
5. **Explain**: "The AI has done its part. Now the job is parked, waiting for a human decision. This could take minutes, hours, or days. JobRunr doesn't care."
6. **Click Approve or Decline**
7. **Show the dashboard**. Job moves to SUCCEEDED or FAILED

### Key talking points
- **Human-in-the-loop.** The job waits indefinitely for a human decision
- **No timeout pressure.** Unlike a regular job, there's no worker thread waiting
- **Audit trail.** JobRunr tracks the full lifecycle: created > processing > processed > succeeded/failed
- **Priority queues.** Approval jobs go to `high-prio` queue, processed before regular jobs

## Closing (the big picture)
> "External Jobs let you bring any external process under JobRunr's umbrella: GPU inference, human approvals, third-party API callbacks, payment confirmations. You get the same dashboard, the same monitoring, the same retry/failure handling, but for work that happens outside your JVM."

## Common questions

**Q: What if the app restarts while a GPU job is running?**
A: The job stays in PROCESSED state in the database. The in-memory prediction tracker is lost, but you could persist prediction IDs to reconnect. In production, webhooks solve this cleanly.

**Q: Can I set a timeout on External Jobs?**
A: Yes, you can configure retries and timeouts like any other JobRunr job. If the external system never responds, the job will eventually fail.

**Q: What's the difference between PROCESSING and PROCESSED?**
A: PROCESSING = a worker is actively running the trigger method. PROCESSED = the trigger finished, and the job is parked waiting for an external signal.

**Q: Why priority queues in this demo?**
A: To show that External Jobs work seamlessly with other JobRunr Pro features. GPU and approval jobs are `high-prio`, so they're picked up before any `low-prio` background work.
