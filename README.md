# JobRunr Pro: External Jobs Demo

A Spring Boot demo showcasing **[External Jobs](https://www.jobrunr.io/en/documentation/pro/external-jobs/)** in JobRunr Pro 8.5. External Jobs are jobs that are triggered by JobRunr but completed by an external system or a human decision.

> **Learn more:** [External Jobs Guide](https://www.jobrunr.io/en/guides/advanced/external-jobs/), a step-by-step walkthrough of the External Jobs API.

## Scenarios

| Scenario | What happens |
|---|---|
| **GPU Video Generation** | A text prompt is sent to [Replicate](https://replicate.com) which runs `lightricks/ltx-2.3-fast` on a real GPU. JobRunr tracks the long-running prediction as an External Job. A poller detects completion and signals the job. |
| **AI Content Approval** | AI generates marketing copy with a confidence score. The job enters PROCESSED state and waits for a human to approve or decline. The decision signals the External Job as succeeded or failed. |

Both scenarios use **priority queues**. GPU and approval jobs are enqueued on the `high-prio` queue.

## Tech Stack

- Java 21, Spring Boot 3.5.6, Thymeleaf
- JobRunr Pro 8.5.0
- PostgreSQL 17 (via Docker Compose)
- Replicate API for real GPU inference

## Prerequisites

- Java 21+
- Docker (for PostgreSQL)
- A [Replicate](https://replicate.com) API token (for the GPU scenario)
- JobRunr Pro Maven credentials (`mavenUser` / `mavenPass` in `gradle.properties`)

## Running

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Set your Replicate token
export REPLICATE_API_TOKEN=r8_your_token_here

# 3. Run the app
./gradlew bootRun
```

- App: http://localhost:8080
- JobRunr Dashboard: http://localhost:8080/dashboard

## How It Works

### External Jobs API

```java
// Create an External Job. The trigger method runs, then the job
// enters PROCESSED state and waits for an external signal.
var jobId = BackgroundJob.create(anExternalJob()
    .withName("GPU Video: " + prompt)
    .withLabels("gpu", "replicate")
    .withQueue("high-prio")
    .withDetails(() -> triggerPrediction(prompt)));

// Inside the trigger, get the job context from the current thread
var jobContext = ThreadLocalJobContext.getJobContext();
UUID jobKey = jobContext.getJobId();

// Signal completion from outside
BackgroundJob.signalExternalJobSucceeded(jobId, "Video generated");
BackgroundJob.signalExternalJobFailed(jobId, "Prediction failed");
```

Neither scenario manually generates a job key. `BackgroundJob.create()` returns the assigned `JobId`, and trigger methods access the job context via `ThreadLocalJobContext` or the `JobContext` parameter.

### GPU Video Generation (`/gpu`)

1. User submits a text prompt
2. JobRunr creates an External Job whose trigger calls the Replicate API
3. The trigger retrieves its own job ID via `ThreadLocalJobContext` to track the prediction
4. Job enters **PROCESSED** state (no worker threads blocked)
5. A recurring poller checks Replicate every 5 seconds
6. On completion, the poller signals the External Job as **SUCCEEDED**

### AI Content Approval (`/approvals`)

1. User clicks "Generate AI Content"
2. JobRunr creates an External Job whose trigger generates marketing copy and stores content, confidence score, and recommendation as **job metadata** via `JobContext.saveMetadata()`
3. Job enters **PROCESSED** state, waiting for a human decision
4. The approval dashboard queries JobRunr's `StorageProvider` for PROCESSED jobs with the `ai-review` label. No separate database table needed
5. Human clicks Approve or Decline, which signals the External Job as **SUCCEEDED** or **FAILED**

### StorageProvider as the Source of Truth

The approval flow uses JobRunr's own storage to list pending reviews instead of maintaining a separate table:

```java
// Query all PROCESSED jobs with the "ai-review" label
var request = aJobSearchRequest(StateName.PROCESSED)
    .withLabel("ai-review").build();
List<Job> pendingJobs = storageProvider.getJobList(
    request, Paging.AmountBasedList.descOnUpdatedAt(50));
```

This means the approval UI is powered entirely by JobRunr, showcasing how `StorageProvider`, `JobSearchRequest`, labels, and job metadata work together.

## Project Structure

```
src/main/java/org/jobrunr/demo/
├── ExternalJobsDemoApplication.java   # Spring Boot entry point
├── approval/
│   ├── AiApprovalService.java         # Human-in-the-loop flow (StorageProvider + metadata)
│   └── ApprovalController.java        # Web endpoints for /approvals
└── gpu/
    ├── GpuJob.java                    # Record for GPU job state
    ├── GpuJobService.java             # GPU flow (Replicate + poller)
    ├── GpuController.java             # Web endpoints for /gpu
    └── ReplicateService.java          # Replicate API client
```

## Limitations

This is a demo application. The following trade-offs are intentional:

- **In-memory tracking for GPU jobs.** Active GPU predictions are tracked in a `ConcurrentHashMap`. If the app restarts mid-prediction, the in-memory state is lost (the job stays PROCESSED in JobRunr but is never signaled). In production, you'd persist prediction IDs or use webhooks.

- **In-memory cache for completed reviews.** JobRunr clears job metadata when a job succeeds or fails. Completed approval reviews are cached in a `CopyOnWriteArrayList` for the "Review History" section and lost on restart. In production, you'd persist completed review data separately.

- **Polling instead of webhooks for GPU.** The `GpuJobService` polls Replicate every 5 seconds via a recurring job. In production, you'd use Replicate's webhook support to avoid polling entirely.

- **Simulated AI content generation.** The approval flow doesn't call a real AI model. It picks random marketing copy templates and generates a random confidence score.

- **No authentication.** The approval UI has no access control. Anyone with access can approve or decline content.

- **Single-node only.** The in-memory state in both services means this demo should only run on a single app instance.

- **Replicate token required for GPU.** The GPU scenario requires a valid `REPLICATE_API_TOKEN`. Without it, the GPU page will load but video generation will fail. The approval scenario works without any external API.
