# YouTube Clone — Learnings

## Architecture Overview

Server handles metadata only — video bytes never touch it during playback.

- **Upload**: client gets a pre-signed S3 PUT URL from the server, uploads directly to S3, then registers metadata via `POST /videos`
- **Playback**: client gets a pre-signed S3 GET URL, streams directly from S3
- **Analytics**: server updates Redis histogram, Kafka event published, periodic flush to Postgres

This minimises server load for the high-traffic operations (upload/playback bandwidth).

---

## Viewing Histogram

The histogram shows which parts of a video were watched the most (like YouTube's red bar under videos).

**Implementation:**
- Video duration is divided into 100 equal buckets (each = 1% of the video)
- When a view event arrives `{ startSeconds, endSeconds }`, the watched range is converted to a bucket range and each bucket is incremented in Redis via `HINCRBY`
- Redis key: `histogram:{videoId}` (a hash, field = bucket index, value = count)
- `HINCRBY` is atomic, so concurrent view events from many users are safe

**Why Redis for this:**
- `HINCRBY` is O(1) — fast under any write load
- `HGETALL` returns all 100 buckets in one round trip for reads
- Redis accumulates all events continuously — it is never reset, just keeps incrementing

---

## Persistence: Redis → Postgres Flush

Redis holds the live running total. Postgres is the durable backup.

**`HistogramFlushService`** runs every 60 seconds (`@Scheduled(fixedDelay)`):
1. Reads all video IDs from Redis Set `pending-histogram-flush` (populated on every view event)
2. Snapshots each video's Redis hash into the `video_histograms` Postgres table
3. Removes the video ID from the pending set

`fixedDelay` (not `fixedRate`) is used so runs cannot overlap if a flush takes longer than expected.

**Why overwriting Postgres is correct:**
Redis accumulates all events since it started, so the Redis hash always holds the full running total — not just the last minute's events. Flushing = snapshotting the latest total, which is always correct.

**Redis restart recovery:**
If Redis restarts, the hash is gone. The first `recordView` call for a video detects the missing key and seeds Redis from Postgres before incrementing (`putIfAbsent` to handle concurrent warmers safely). The chain of accumulation then continues correctly from the persisted baseline.

`getHistogram` has the same fallback — if Redis is cold, it reads from Postgres and re-warms the key.

---

## Why Raw View Events Are Not Saved to Postgres

At scale, saving one DB row per view event is impractical (billions of writes/day). Only the aggregated histogram is persisted to Postgres.

Raw events are published to Kafka. In production, the Kafka consumer would forward them to cold storage (S3/GCS) for offline use: ML training, recommendations, ad targeting. This project leaves that as a stub.

---

## Kafka → Flink → Bigtable (Real YouTube Pattern)

This project uses: **Kafka → Redis (aggregation) → Postgres (flush)**

Real YouTube uses: **Kafka → Flink → Bigtable**

They are the same pattern at different scales:

| This project | Real YouTube |
|---|---|
| Kafka | Kafka |
| Redis `HINCRBY` (in-memory accumulation) | Flink in-memory aggregation window |
| `HistogramFlushService` (single-threaded scheduled job) | Flink periodic flush (distributed, millions of events/sec) |
| Postgres | Bigtable / ClickHouse |

The key insight is that **Flink sits between Kafka and the DB to aggregate events before they hit storage**. Without it:
```
100,000 concurrent viewers → 100,000 DB writes/sec → DB falls over
```
With it:
```
100,000 concurrent viewers → Kafka (buffer)
                                  ↓
                             Flink aggregates over a time window
                             "bucket 42 incremented 8,000 times this window"
                                  ↓
                             1 write per bucket per window → DB handles it
```

Kafka absorbs bursts so no events are lost if Flink is briefly slow. Flink is distributed so it scales horizontally. Bigtable/ClickHouse handle far higher write throughput than Postgres.

---

## Flush Interval

60 seconds is an arbitrary default for this project. In production it depends on:
- **Acceptable data loss window** on Redis failure
- **DB write throughput capacity**

Real YouTube likely doesn't use a scheduled flush at all — Flink processes the Kafka stream continuously, so the effective flush interval is seconds, not minutes.

The interval can be made configurable without redeploying:
```kotlin
@Scheduled(fixedDelayString = "\${analytics.histogram.flush-interval-ms:60000}")
fun flush() { ... }
```

---

## Watch History — lastWatchedSeconds (Resume Position)

Tracks where the user left off so playback can resume from that point.

**Model:** `watch_history` table has a `(user_id, video_id)` unique constraint. `lastWatchedSeconds` is a last-write-wins scalar — no aggregation needed, just the most recent position.

**Two triggers that create/update the record:**
1. `POST /videos/{id}/watched` — called by the client after 10s of playback; creates the row with `lastWatchedSeconds = 0`
2. `POST /analytics/updateLastWatchedSeconds` — heartbeat called every 30s; updates position

**Why not Kafka for this:**
The histogram uses Kafka because many clients concurrently increment shared counters and the events fan out to downstream consumers (ML, recommendations). `lastWatchedSeconds` is a simple scalar per user/video pair — last-write-wins, no fan-out needed. Kafka adds ordering and durability that bring no benefit here.

---

## lastWatchedSeconds — Redis Write-Through Pattern

Direct Postgres writes on every heartbeat would be ~33k writes/sec at 1M concurrent viewers (one per user per 30s). Instead:

**Write path:**
- Heartbeat → `SET watch-resume:{userId}:{videoId} {seconds} EX 86400` in Redis (in-memory, fast)
- Also `SADD pending-watch-resume-flush {userId}:{videoId}` to track what needs flushing
- `recordView` (called on pause/close) also calls `updateLastWatchedSeconds` with `endSeconds` so position is captured even if the client closes before the next heartbeat

**Flush path (`WatchResumeFlushService`, every 30s):**
1. SSCAN `pending-watch-resume-flush` — cursor-based, non-blocking
2. `MGET` all `watch-resume:*` keys — single Redis round-trip
3. `jdbcTemplate.batchUpdate` with `INSERT ... ON CONFLICT DO UPDATE` — single DB round-trip
4. `SREM` all processed entries

This gives crash-safety within one flush interval (30s) with minimal DB pressure.

---

## SMEMBERS vs SSCAN

`SMEMBERS` is O(N) and **blocks the Redis event loop** for its entire duration — all other commands queue behind it. At 3000 entries this might be 5ms; at millions it could be seconds, starving every concurrent request.

`SSCAN` iterates with a cursor in steps (hint: `COUNT 1000`). Redis processes ~1000 members, returns a cursor, and **releases the event loop** between steps. Other commands run in the gaps. Spring's `Cursor` handles the pagination internally — `.forEach` collects all entries across multiple round-trips transparently.

`SSCAN` does not chunk the *processing* — all entries are still collected into memory before the batch runs. The benefit is purely that Redis stays responsive during the scan.

---

## N Individual DB Writes vs JDBC Batch Upsert

At 3000 entries, old vs new at ~1ms DB round-trip:

| | Old (N individual) | New (batch) |
|---|---|---|
| Redis reads | 3000 × GET = ~1,500ms | 1 × MGET = ~3ms |
| DB reads | 3000 × SELECT = ~3,000ms | 0 |
| DB writes | 3000 × UPDATE = ~3,000ms | 1 batch ≈ ~100ms |
| **Total** | **~7.5s** | **~107ms** |

~70x faster. The flush's `fixedDelay` clock doesn't start until the previous run finishes, so a 7.5s flush at 30s delay means the effective interval becomes 37.5s — and worsens under load.

The batch upsert pattern:
```sql
INSERT INTO watch_history (id, user_id, video_id, watched_at, last_watched_seconds)
VALUES (gen_random_uuid(), ?, ?, NOW(), ?)
ON CONFLICT (user_id, video_id) DO UPDATE SET last_watched_seconds = EXCLUDED.last_watched_seconds
```

One statement handles both the first-time insert and subsequent updates. No prior SELECT needed.

---

## Rate Limiting — Sliding Window Log with Redis ZSET

Each user gets a ZSET keyed by `rate-limit:{action}:{userId}`. Every request is stored as a member scored by its timestamp in milliseconds. The window slides with time — old entries are evicted on each request, so the count always reflects only the last N milliseconds.

**The three operations per request:**
1. `ZREMRANGEBYSCORE key 0 (now - windowMs)` — evict requests older than the window
2. `ZADD key score=now member="{now}:{uuid}"` — record this request
3. `ZCARD key` — count remaining entries; if over limit, reject

**Why a nonce (`{now}:{uuid}`) as the member:**
ZSET members must be unique. Two requests at the exact same millisecond would have the same timestamp, so using the timestamp alone as the member would cause one to overwrite the other — undercounting requests. The UUID suffix makes every entry unique.

**Why a Lua script:**
Without it, two concurrent requests could both read `ZCARD = 2` (under a limit of 3), both pass, and both add their entries — resulting in 4 entries with the limit bypassed. The Lua script runs atomically on the Redis event loop; no other command can interleave between the three steps.

**Walk-through at limit=3, window=60s:**
```
t=1000  ZREM(nothing) ZADD(score=1000) ZCARD=1 → ✅ allowed
t=2000  ZREM(nothing) ZADD(score=2000) ZCARD=2 → ✅ allowed
t=3000  ZREM(nothing) ZADD(score=3000) ZCARD=3 → ✅ allowed
t=3001  ZREM(nothing) ZADD(score=3001) ZCARD=4 → 🚫 blocked (4 > 3)
t=70000 ZREM(evicts scores 1000,2000,3000,3001) ZADD(score=70000) ZCARD=1 → ✅ allowed
```

`PEXPIRE` is set to `windowMs * 2` so keys from idle users auto-delete rather than sitting in Redis indefinitely.

**Applied via `HandlerInterceptor`** registered on `/videos/*/reactions/**`. Runs after `JwtFilter` so `SecurityContextHolder` is populated and the key can be per-userId. Falls back to IP for unauthenticated requests.

**Production alternative:** Bucket4j — a standard Java rate limiting library with Redis backend support, configurable as a servlet filter. Hides the ZSET/Lua details but handles edge cases and Redis Cluster routing automatically.

---

## When to Use Kafka vs Redis vs DB (Interview Guide)

### Write tier

**Write directly to DB** when data is transactional, needs consistency, or is low frequency.
- Examples: user registration, video upload, reactions
- Correctness matters more than throughput; these happen rarely enough that DB latency is fine

**Write to Redis first, flush to DB on a schedule** when you have high-frequency writes to the same record and can tolerate eventual persistence.
- Examples: watch resume position (heartbeat every 30s per active user), view counts, histograms
- Key property: **write amplification reduction** — 10,000 active users × 30s heartbeat = 10,000 DB writes/30s collapsed into one batch upsert
- Use a **pending dirty set** alongside the value keys (e.g. `pending-watch-resume-flush`). The flush service reads only dirty entries rather than scanning all value keys. Without it, unchanged keys (e.g. a user who paused and walked away — their key persists in Redis with a 1-day TTL) would be re-flushed on every tick for no reason.

**Write to Kafka, process async** when the downstream work is decoupled, needs fan-out to multiple consumers, or needs replayability.
- Examples: video registered → trigger transcoding + thumbnail generation; view events → ML recommendations, cold storage
- Latency tolerance: minutes to hours
- Kafka is for *processing pipelines*, not *storing the latest value*

### Read tier

**Read from Redis** when you need sub-millisecond latency that Postgres can't reliably provide under load, or when the data is already in Redis from the write path.
- Rate limit counters: checked and incremented on every request — microseconds matter, not milliseconds
- View counts and resume positions: already written to Redis — serve from there, fall back to DB on cold cache. Don't go back to Postgres for data that's more up-to-date in Redis anyway.

**Read directly from DB** for low-frequency reads or when consistency is required. A primary key lookup in Postgres is already fast — don't add caching complexity without evidence of a bottleneck.

**Don't bother caching:**
- Simple PK lookups — already fast
- Elasticsearch query results — ES has its own query cache
- Paginated lists — any new record invalidates the first page, making invalidation impractical

### The immutability insight

If a volatile field (e.g. view count) lives on the main entity row, that row gets hot under concurrent writes and can't be safely cached — any cache entry goes stale immediately. Move volatile fields to a separate table (`video_view_counts`) so the core entity row becomes effectively immutable after creation. An immutable row can be cached indefinitely with no invalidation logic needed.

### Summary

| Scenario | Tool | Why |
|---|---|---|
| Transactional / low frequency | DB directly | Correctness over throughput |
| High-frequency updates to same key | Redis → flush to DB | Collapse N writes into 1 batch |
| Decoupled processing / fan-out | Kafka | Multiple consumers, replayability |
| Sub-ms read latency needed | Redis | Microseconds vs milliseconds |
| Data already in Redis | Read from Redis | More up-to-date than DB anyway |
| Simple PK lookup | DB directly | Already fast, no cache needed |
