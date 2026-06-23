# YouTube Clone — Backend API

A backend-only YouTube clone built in Kotlin with Spring Boot. Designed as an interview prep project to demonstrate real-world system design decisions in code.

## Stack

| Layer | Technology |
|---|---|
| API | Spring Boot (Kotlin) |
| Database | PostgreSQL |
| Cache / write buffer | Redis |
| Event streaming | Kafka |
| Video storage | AWS S3 |
| Search | Elasticsearch |
| Auth | JWT (stateless) |

## Key Design Decisions

**Redis as write buffer.** View counts, watch resume positions, and engagement histograms are written to Redis first and flushed to Postgres on a schedule (every 30s for resume, every minute for histograms/counts). This avoids a DB write on every playback heartbeat.

**View count deduplication.** A view is counted at most once per user per video per 24-hour window, enforced atomically via `SET NX` in Redis.

**Cursor-based pagination.** The video list uses a keyset cursor (`createdAt + id`) instead of `OFFSET` to avoid slow scans on large tables.

**Histogram bucketing.** Each video's watch engagement is tracked across 100 buckets representing equal slices of the video duration. Buckets accumulate in Redis and are flushed to Postgres periodically.

**Kafka for async work.** When a video is registered, a `video-registered` event is published. The consumer stub is where transcoding and thumbnail generation would be triggered in production. View events are published to `view-event` for downstream ML/recommendations.

**Rate limiting.** Reactions are rate-limited to 20 per minute per authenticated user (or IP for anonymous). Enforced with a Redis sliding window.

**Feature flags.** `FeatureFlagClient` subscribes to the [feature-flag-service](https://github.com/alias8/feature-flag-service-kotlin) SSE stream on startup and caches all flags in memory. When a flag is evaluated for a user, the logic is:

1. Flag not found in cache → `false`
2. Flag's `enabled` field is `false` → `false` (global kill-switch)
3. User has a per-user override → return that override directly
4. Otherwise: hash `"flagName:userId"` with MurmurHash3 (32-bit) to produce a stable bucket in 0–99. If the bucket is below the flag's `rolloutPercentage`, the flag is `true` for that user.

The hash includes the flag name so that a user who lands in the bottom 10% for one flag doesn't necessarily land there for every flag. The same user always gets the same bucket for a given flag (deterministic), so their experience doesn't flip between requests. Flag updates are pushed from the service as SSE `patch` events and update the cache in-place, so rollout changes take effect immediately without restarting the app.

## Assumptions

- No frontend — this is a pure REST API.
- S3 stores raw video files. Transcoding is out of scope; the Kafka consumer is a stub.
- JWT tokens are stateless with a 24-hour expiry. No refresh tokens.
- Unauthenticated users can browse, search, and watch videos but cannot react, upload, or have their progress saved.
- `lastWatchedSeconds` is only persisted for authenticated users.

## User Flow

### Registration / Login

```
POST /auth/register   { username, password }  →  { token }
POST /auth/login      { username, password }  →  { token }
```

All subsequent requests that require auth pass `Authorization: Bearer <token>`.

### Uploading a Video

```
GET  /videos/upload-url?filename=video.mp4   →  { uploadUrl, s3Key }
```

Client uploads directly to S3 using the presigned URL, then registers the video:

```
POST /videos   { title, description, s3Key, durationSeconds }
```

This saves the video to Postgres, publishes a `video-registered` Kafka event (triggers transcoding in production), and indexes the video in Elasticsearch.

### Browsing & Search

```
GET /videos?cursor=<cursor>&limit=20    →  paginated video list
GET /videos/search?q=<query>&limit=20  →  full-text search results (Elasticsearch)
```

### Watching a Video

```
GET /videos/{id}               →  video metadata + lastWatchedSeconds (if returning viewer)
GET /videos/{id}/playback-url  →  signed S3 URL (60-minute expiry)
```

`lastWatchedSeconds` is returned directly in the video response so the player can seek to the correct position before playback starts — no extra round-trip needed.

While the video plays, the client sends:

```
POST /videos/{id}/watched                          (after 10 seconds of playback — records in watch history)
POST /analytics/updateLastWatchedSeconds           (every 30 seconds — resume heartbeat)
POST /analytics/view  { startSeconds, endSeconds } (on pause/stop — updates engagement histogram)
```

### Reactions

```
POST   /videos/{id}/reactions/like
POST   /videos/{id}/reactions/dislike
DELETE /videos/{id}/reactions
GET    /videos/{id}/reactions   →  { likes, dislikes, userReaction }
```

### Watch History

```
GET /users/me/history?page=0&size=20  →  list of watched videos with lastWatchedSeconds
```

### Engagement Histogram

```
GET /analytics/{videoId}/histogram  →  100-bucket array of view counts across the video duration
```

## Running Locally

Requires PostgreSQL, Redis, Kafka, and Elasticsearch running locally (defaults in `application.properties`).

```
./gradlew bootRun
```

### Environment Variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/youtube` | Postgres connection |
| `DATABASE_USER` | `jameskirk` | Postgres user |
| `DATABASE_PASSWORD` | `password` | Postgres password |
| `JWT_SECRET` | `change-me-...` | JWT signing secret |
| `AWS_S3_BUCKET` | `youtube-videos-bucket` | S3 bucket name |
| `AWS_REGION` | `us-east-1` | AWS region |
| `AWS_ACCESS_KEY` | | AWS access key |
| `AWS_SECRET_KEY` | | AWS secret key |
| `ELASTICSEARCH_URI` | `http://localhost:9200` | Elasticsearch endpoint |
