# External Jobs Demo: Project Guide

## What is this?
A Spring Boot demo app showcasing **JobRunr Pro 8.5 External Jobs**. These are jobs that are triggered by JobRunr but completed by an external system or a human decision.

## Tech stack
- Java 21, Spring Boot 3.5.6, Thymeleaf
- JobRunr Pro 8.5.0 (private Maven repo, credentials in `gradle.properties`)
- PostgreSQL 17 (via `docker-compose.yml`)
- Replicate API for real GPU inference (model: `lightricks/ltx-2.3-fast`)
- Google Gemini API for async batch generation with webhooks
- `spring-dotenv` loads `.env` (gitignored) for `REPLICATE_API_TOKEN`, `GEMINI_API_KEY`, `GEMINI_PUBLIC_URL`

## Running
```bash
docker compose up -d
# Either export env vars or put them in .env (gitignored)
./gradlew bootRun
```
App: http://localhost:8080 | Dashboard: http://localhost:8080/dashboard

For the Gemini scenario, expose the app first:
```bash
ngrok http 8080
# copy the https URL into GEMINI_PUBLIC_URL in .env
```
On startup, the app calls `POST /v1/webhooks` once and persists the signing secret to `.gemini-webhook.json` (gitignored). Subsequent boots reuse it.

## Key External Jobs API
```java
// Create an External Job (JobRunr assigns the ID)
var jobId = BackgroundJob.create(anExternalJob()
    .withName("GPU Video: " + prompt)
    .withLabels("gpu", "replicate")
    .withQueue("high-prio")
    .withDetails(() -> triggerPrediction(prompt)));

// Inside a trigger method, get the job context from the current thread
var jobContext = ThreadLocalJobContext.getJobContext();
UUID jobKey = jobContext.getJobId();

// Or receive JobContext as a parameter (auto-injected by JobRunr)
public void analyzeContent(String productName, JobContext jobContext) {
    jobContext.saveMetadata("content", generatedContent);
}

// Signal completion from outside
BackgroundJob.signalExternalJobSucceeded(jobId, "message");
BackgroundJob.signalExternalJobFailed(jobId, "reason");
```

## Architecture notes
- No scenario generates its own job key. `BackgroundJob.create()` returns the assigned `JobId`, and trigger methods access their job ID via `ThreadLocalJobContext` (GPU/Gemini) or the `JobContext` parameter (approval).
- GPU jobs use an in-memory `ConcurrentHashMap<UUID, GpuJob>` to track active predictions. This means active jobs are lost on restart (completed jobs in Replicate are not re-linked). This is fine for a demo.
- The poller in `GpuJobService` checks Replicate every 5s via a recurring job. In production, you'd use webhooks to avoid polling.
- The approval flow uses **JobRunr as the sole source of truth**, with no separate database table. AI-generated content is stored as job metadata via `JobContext.saveMetadata()`, and the dashboard queries `StorageProvider` for PROCESSED jobs with the `ai-review` label. Completed reviews are cached in-memory (lost on restart).
- The Gemini flow contrasts with the GPU flow: **Google pushes a webhook**, we don't poll. `GeminiWebhookSetup` registers a static webhook on startup, `GeminiBatchService` uploads JSONL + creates a batch, and `GeminiWebhookController` verifies the HMAC-SHA256 signature (per the [Standard Webhooks](https://www.standardwebhooks.com) spec) before signaling the External Job.

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

## Scenario 3: Gemini Async with Webhooks (~2 min)

### Setup
1. Run `ngrok http 8080` and copy the https URL into `GEMINI_PUBLIC_URL` in `.env`
2. Set `GEMINI_API_KEY` in `.env` (from https://aistudio.google.com/apikey)
3. `./gradlew bootRun` — on startup, the app registers a webhook with Gemini and stores the signing secret in `.gemini-webhook.json` (gitignored)
4. Open http://localhost:8080/gemini

### Demo flow
1. **Show the green banner.** "Webhook registered" with the webhook id and URL — emphasize the one-time setup
2. **Submit a prompt**, e.g. "Write a one-sentence tagline for a developer-focused job scheduler"
3. **Show the dashboard.** External Job is PROCESSING (we're uploading the JSONL + creating the batch)
4. **Show the dashboard again.** Job is now PROCESSED — we're done with our worker thread, waiting for Google
5. **Wait ~10–30s.** Google fires `batch.succeeded` webhook to our app
6. **Show the response.** Generated text appears with the batch id
7. **Show the dashboard.** Job moved to SUCCEEDED

### Key talking points
- **Push, not poll.** Compare to the GPU demo where we poll Replicate every 5s — here Google pushes
- **Standard Webhooks.** HMAC-SHA256 signature verification per [standardwebhooks.com](https://www.standardwebhooks.com) — same spec used by Stripe, Vercel, Resend, etc.
- **Stateless on our side.** We don't need to remember to check anything; the webhook is the trigger
- **End-to-end signed.** Replay protection via the 5-minute timestamp window, signature verified before doing any work
- **One-time registration.** `POST /v1/webhooks` once → Gemini stores it for the project → all future batches notify us

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
