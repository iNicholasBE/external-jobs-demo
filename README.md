# JobRunr Pro — External Jobs Demo

Two scenarios showcasing **External Jobs** in [JobRunr Pro 8.5](https://www.jobrunr.io/en/documentation/pro/external-jobs/):

| Scenario | What happens |
|---|---|
| **GPU Video Generation** | A text prompt is sent to [Replicate](https://replicate.com) which runs `lightricks/ltx-2.3-fast` on a real GPU. JobRunr tracks the long-running prediction as an External Job. A poller detects completion and signals the job. |
| **AI Content Approval** | AI generates marketing copy with a confidence score. A human reviewer approves or declines. The decision signals the External Job as succeeded or failed. |

Both scenarios also demonstrate **priority queues** — review and GPU jobs are enqueued on the `high-prio` queue.

## Prerequisites

- Java 21+
- Docker (for PostgreSQL)
- A [Replicate](https://replicate.com) API token (for GPU video generation)
- JobRunr Pro Maven credentials (set `mavenUser` / `mavenPass` in `gradle.properties`)

## Running

```bash
# 1. Start PostgreSQL
docker compose up -d

# 2. Set your Replicate token (or edit application.properties)
export REPLICATE_API_TOKEN=r8_your_token_here

# 3. Run the app
./gradlew bootRun
```

Open [http://localhost:8080](http://localhost:8080) — the landing page links to both demos.

The embedded JobRunr dashboard is at [http://localhost:8080/dashboard](http://localhost:8080/dashboard).
