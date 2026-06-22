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
