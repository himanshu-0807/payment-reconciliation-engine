# Progress

Working log so the build can be resumed. Every "done" item was run locally on macOS arm64
(Docker 29.8, Java 21.0.10, Maven 3.9.9, Node 24).

## Done
- Phase 1: Maven multi-module skeleton (common, gateway-sim, bank-sim, recon-service);
  docker-compose with Postgres 16, Kafka 3.9 (KRaft, no ZooKeeper) and LocalStack 3.8 (S3).
  Verified: all three containers healthy, `psql` query, topic create on Kafka, `awslocal s3 ls`
  shows bucket `recon-data`. Deterministic world generator + 4 unit tests passing.

## Next
- Phase 2: Flyway schema, ledger, idempotent Kafka callback consumer.

## Known issues
- Postgres is published on host port 5433 (5432 is often taken by a local Postgres).
