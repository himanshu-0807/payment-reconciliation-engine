# Progress

Working log so the build can be resumed. Every "done" item was run locally on macOS arm64
(Docker 29.8, Java 21.0.10, Maven 3.9.9, Node 24).

## Done
- Phase 1: Maven multi-module skeleton (common, gateway-sim, bank-sim, recon-service);
  docker-compose with Postgres 16, Kafka 3.9 (KRaft, no ZooKeeper) and LocalStack 3.8 (S3).
  Verified: all three containers healthy, `psql` query, topic create on Kafka, `awslocal s3 ls`
  shows bucket `recon-data`. Deterministic world generator + 4 unit tests passing.

- Phase 2: Flyway V1 schema (ledger, dedupe tables, sources, recon, ops queue, append-only audit
  via trigger, users). Batch Kafka consumer with event-id dedupe + Kafka-position redelivery
  detection. Verified: LedgerStateTest (6) + CallbackConsumerIT (3, Testcontainers Postgres+Kafka).

## Next
- Phase 3: gateway-sim and bank-sim with the seeded fault injector and ground truth.

## Known issues
- Stopping and restarting the Spring listener container in-process did not resume fetching after an
  external offset reset in a test; the redelivery test therefore replays the topic from offset 0
  with a separate consumer and feeds the real records through the same processor.
- Postgres is published on host port 5433 (5432 is often taken by a local Postgres).
