# JobRunr Pro: External Jobs Demo

> **Watch the video:** [External Jobs in JobRunr Pro](https://youtu.be/lDc66lktbfs) on YouTube — this repository contains the code shown in the video.

A Spring Boot demo showcasing **[External Jobs](https://www.jobrunr.io/en/documentation/pro/external-jobs/)** in JobRunr Pro 8.5. External Jobs are jobs that are triggered by JobRunr but completed by an external system or a human decision.

> **Learn more:** [External Jobs Guide](https://www.jobrunr.io/en/guides/advanced/external-jobs/), a step-by-step walkthrough of the External Jobs API.

## Scenarios

| Scenario | What happens |
|---|---|
| **GPU Video Generation** | A text prompt is sent to [Replicate](https://replicate.com) which runs `lightricks/ltx-2.3-fast` on a real GPU. JobRunr tracks the long-running prediction as an External Job. A poller detects completion and signals the job. |
| **AI Content Approval** | AI generates marketing copy with a confidence score. The job enters PROCESSED state and waits for a human to approve or decline. The decision signals the External Job as succeeded or failed. |
| **Gemini Async (Webhooks)** | A prompt is sent to Google Gemini's async batch API. The External Job parks in PROCESSED. When Gemini is done, it POSTs a signed [Standard Webhook](https://www.standardwebhooks.com) back to the app, which verifies the signature and signals the job — no polling. |
| **Gemini Veo (Webhooks)** | A prompt is sent to Veo via `:predictLongRunning`. The External Job parks in PROCESSED. When the video is ready, Google fires a `video.generated` webhook; the app fetches the MP4 and signals the job. Same External Jobs API as the GPU/Replicate demo, but **push** instead of poll. |

All scenarios use **priority queues** — they enqueue on `high-prio`.

## Tech Stack

- Java 21, Spring Boot 3.5.6, Thymeleaf
- JobRunr Pro 8.5.0
- PostgreSQL 17 (via Docker Compose)
- Replicate API for real GPU inference
- Google Gemini API for async batch generation with webhooks

## Prerequisites

- Java 21+
- Docker (for PostgreSQL)
- A [Replicate](https://replicate.com) API token (for the GPU scenario)
- A [Google AI Studio](https://aistudio.google.com/apikey) API key (for the Gemini scenario)
- An `ngrok` tunnel or similar (for the Gemini webhook to reach your laptop)
- JobRunr Pro Maven credentials (`mavenUser` / `mavenPass` in `gradle.properties`)

## Running

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Copy .env.example to .env and fill in your values
cp .env.example .env
```

### Setting up ngrok (required for the Gemini scenarios)

Gemini's webhooks need a publicly reachable HTTPS URL to POST to. Locally we use [ngrok](https://ngrok.com) to expose port 8080.

```bash
# Install (one-time)
brew install ngrok        # or download from https://ngrok.com/download
ngrok config add-authtoken <YOUR_AUTHTOKEN>   # one-time, from https://dashboard.ngrok.com/get-started/your-authtoken

# Start the tunnel — leave this running in its own terminal
ngrok http 8080
```

ngrok prints a forwarding URL like `https://something-something.ngrok-free.app`. Copy that into `.env`:

```ini
GEMINI_API_KEY=...your-key...
GEMINI_PUBLIC_URL=https://something-something.ngrok-free.app
```

If you have a paid plan (or a reserved free dev domain), you can pin a stable URL with `ngrok http --url=<your-domain> 8080` so you don't have to re-edit `.env` every restart.

> **Heads up: port must be 8080.** The Spring Boot app listens on 8080, so `ngrok http 80` will not reach it.

### Boot the app

```bash
./gradlew bootRun
```

- App: http://localhost:8080
- JobRunr Dashboard: http://localhost:8080/dashboard

On startup the app calls `POST https://generativelanguage.googleapis.com/v1beta/webhooks` once to register a webhook for the `batch.*` and `video.generated` events, then writes the signing secret to `.gemini-webhook.json` (gitignored). Subsequent restarts reuse it. If `GEMINI_PUBLIC_URL` changes (or the subscribed events change), the app deletes the old webhook and registers a fresh one automatically.

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

### Gemini Async with Webhooks (`/gemini`)

1. On startup, `GeminiWebhookSetup` registers a static webhook with Gemini. The signing secret is persisted to `.gemini-webhook.json` (gitignored)
2. User submits a prompt
3. JobRunr creates an External Job whose trigger uploads a JSONL file via the Files API and creates a batch job
4. Job enters **PROCESSED** state — we're not polling, we're just waiting
5. Google Gemini finishes the batch and POSTs a signed webhook to `<GEMINI_PUBLIC_URL>/gemini/webhook`
6. `GeminiWebhookController` verifies the HMAC-SHA256 signature (per the [Standard Webhooks](https://www.standardwebhooks.com) spec), downloads the output JSONL, and signals the External Job as **SUCCEEDED**

```java
// Trigger: kick off async work, External Job parks afterwards
public void triggerBatch(String prompt) {
    UUID jobKey = ThreadLocalJobContext.getJobContext().getJobId();
    GeminiClient.GeminiFile uploaded = client.uploadFile(jsonl, "application/jsonl", "...");
    GeminiClient.BatchOp op = client.createBatchFromFile(model, uploaded.name(), "...");
    batchToJob.put(op.name(), jobKey);
}

// Webhook: verify, then signal
public ResponseEntity<String> receive(String webhookId, String timestamp, String signature, String body) {
    setup.verifier().verify(webhookId, timestamp, signature, body);
    String batchId = event.path("data").path("id").asText();
    String outputUri = event.path("data").path("output_file_uri").asText();
    batchService.onBatchSucceeded(batchId, outputUri);
    return ResponseEntity.ok("{\"status\":\"received\"}");
}
```

## Project Structure

```
src/main/java/org/jobrunr/demo/
├── ExternalJobsDemoApplication.java   # Spring Boot entry point
├── approval/
│   ├── AiApprovalService.java         # Human-in-the-loop flow (StorageProvider + metadata)
│   └── ApprovalController.java        # Web endpoints for /approvals
├── gemini/
│   ├── GeminiBatchService.java        # External Job trigger + batch create
│   ├── GeminiClient.java              # Webhooks/Files/Batch HTTP client
│   ├── GeminiConfig.java              # @Value-driven config
│   ├── GeminiJob.java                 # Record for UI display
│   ├── GeminiPageController.java      # /gemini UI
│   ├── GeminiWebhookController.java   # /gemini/webhook receiver
│   ├── GeminiWebhookSetup.java        # Auto-register webhook on startup
│   ├── WebhookSecretStore.java        # Persists signing secret to local file
│   └── WebhookSignatureVerifier.java  # HMAC-SHA256 (Standard Webhooks)
└── gpu/
    ├── GpuJob.java                    # Record for GPU job state
    ├── GpuJobService.java             # GPU flow (Replicate + poller)
    ├── GpuJobController.java          # Web endpoints for /gpu
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
