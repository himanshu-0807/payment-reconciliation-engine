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

- Phase 3: gateway-sim (control API /sim/runs, callbacks to Kafka, settlement CSV per day to S3,
  ground truth CSV) and bank-sim (statements per value date, SOAP getSettlementStatus at /ws).
  Verified live against compose infra: 3 days x 2000 payments -> 6105 callbacks in Kafka, 3 gateway
  files, 8 bank files, ground truth; row counts reconcile with the injected faults; SOAP call via
  curl returns CREDITED. BankSimulatorTest (statements + SOAP via MockWebServiceClient) passes.

- Phase 4: idempotent ingestion (file checksum + row natural keys), pure Classifier with documented
  precedence, PartitionReconciler (key match + mutual-unique fallback), ReconEngine on a bounded
  pool with per-partition transactions and a SHA-256 run digest, auto-fix rules, exception sync,
  ground-truth evaluator. Verified: ClassifierTest (32), AutoFixRulesTest (17), IngestionIT (2),
  ReconEngineIT (4: ground truth precision=recall=1.0 on 12,048 entities; re-run unchanged;
  parallelism 1 vs 8 identical digest + queue; PENDING_EXPECTED -> escalation lifecycle).

- Phase 5: JWT (HS256) security with OPS_ANALYST / OPS_SUPERVISOR / ADMIN, seeded demo users,
  REST API (runs, exceptions with filters/paging, detail side by side, assign/comment/resolve,
  adjustment approval, dashboard summary + 14-day trend, audit, simulator proxy, ground-truth
  evaluation, reset), RFC 7807 errors, OpenAPI/Swagger, SOAP client to bank-sim. Verified: ApiIT (9);
  live run of the 3 jars on compose infra: 5 days x 5000 payments, recon p=8 in 773 ms,
  ground truth precision=recall=1.0 (1407 injected faults, 25,050 entities), SOAP lookup via API.

- Phase 6: React 18 + TypeScript + Vite dashboard (login, money-at-risk KPIs, 14-day trend, breakdown
  by type, aging, auto-fix summary, exceptions table with filters/pagination, detail with ledger /
  gateway / bank side by side + assign/comment/resolve/approve/SOAP check, simulator panel with
  ground-truth table). Recharts. Verified: 37 Jest + RTL tests pass, tsc clean, vite build OK.

## Next
- Phase 7: scale benchmark (14 x 100k), plus re-check of ground-truth / concurrency suites.

## Known issues
- (fixed) @KafkaListener(id=...) silently used the listener id as consumer group; the group is now
  set explicitly. The redelivery test replays the topic from offset 0 with a separate consumer.
- Postgres is published on host port 5433 (5432 is often taken by a local Postgres).
