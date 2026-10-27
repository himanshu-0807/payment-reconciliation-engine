# Design

## The problem in one paragraph

A merchant sees each digital payment three times: in its own **ledger** (built from the gateway's
webhook callbacks), in the **gateway's settlement file** (what the gateway says it captured and
paid out, net of fees), and on the **bank statement** (what actually arrived, identified by a UTR).
These three record sets disagree all the time. Callbacks get lost, duplicated or arrive out of
order. Fees get re-rounded. Settlements arrive late, twice, or not at all. Sometimes money arrives
that nobody can explain. The engine matches the three sets, decides what went wrong with every
payment, fixes the cases that are safe to fix automatically, queues the rest for people, and keeps
one number current: the money at risk.

## Components

| Module | Role |
|---|---|
| `common` | Money helpers, the deterministic world generator (the simulator core), CSV row formats, object-store abstraction (S3 or local filesystem), SOAP contract classes |
| `gateway-sim` | Control API `/sim/runs`. Publishes callbacks to Kafka `gateway.callbacks`, writes `gateway/settlements/<date>.csv` and the ground truth to S3, then asks bank-sim for the matching statements |
| `bank-sim` | Regenerates the same world from the same seed and writes `bank/statements/<value-date>.csv`. Serves SOAP `getSettlementStatus(utr)` at `/ws` |
| `recon-service` | Ledger consumer, S3 ingestion, reconciliation engine, auto-fix rules, ops queue, audit log, REST API with JWT, SOAP client, dashboard queries, ground-truth evaluator. Publishes `recon.exceptions` |
| `web` | React + TypeScript dashboard served by nginx, which proxies `/api` to recon-service |

## Data model

All money is `BIGINT` paise plus a `CHAR(3)` currency. No column, field or computation uses
floating point for money; even the UI formats paise with integer division
(`web/src/format.ts`).

| Table | Purpose | Key |
|---|---|---|
| `ledger_entry` | merchant ledger, one row per payment | `payment_id` |
| `processed_event` | dedupe table: one row per callback event id, with the Kafka partition/offset that was applied | `event_id` |
| `duplicate_delivery` | extra deliveries of a known event | `(kafka_partition, kafka_offset)` |
| `gateway_txn` | gateway settlement rows | `gateway_txn_id` |
| `bank_txn` | bank statement credits | `bank_ref` (UTR is **not** unique: duplicates are real) |
| `ingested_file` | every file ingested, with checksum and statement date | `(source, object_key, checksum)` |
| `recon_run` | run history: range, as-of, parallelism, timings, result digest | `id` |
| `recon_result` | latest outcome per entity (payment or unmatched credit): class, reason, confidence, findings, the three amounts, amount at risk | `(entity_type, entity_id)` |
| `recon_exception` | the ops queue | `id`; unique `(entity_type, entity_id, exception_class)` |
| `exception_comment` | analyst comments | `id` |
| `adjustment_journal` | signed adjustment entries for fee/rounding differences | `id`; unique `exception_id` |
| `audit_log` | append-only history of every engine and user action | `id`; a trigger rejects `UPDATE`, `DELETE` and `TRUNCATE` |
| `app_user` | demo users with BCrypt hashes | `username` |

Schema: `recon-service/src/main/resources/db/migration/V1..V3`. Flyway owns the schema and
Hibernate runs with `ddl-auto=validate`.

**JPA vs JDBC.** Ops-domain entities (exceptions, comments, adjustments, runs, users, audit reads)
use Spring Data JPA, with `@Version` optimistic locking on exceptions. The high-volume paths
(callback consumer, ingestion, partition reads and writes) use `JdbcTemplate` batch statements,
because moving 1.4M rows through Hibernate's persistence context would cost memory and time for no
benefit.

## Matching

1. **Ledger ↔ gateway:** `ledger.payment_id = gateway.merchant_order_id`. If the gateway row has no
   order id, fall back to `ledger.gateway_txn_id = gateway.gateway_txn_id`.
2. **Gateway ↔ bank:** `gateway.settlement_ref = bank.utr`, considering only credits with
   `value_date <= asOf`.
3. **Fallback (keys missing):** a gateway row with no UTR match, and a bank credit with no UTR, are
   matched when the merchant, the exact net amount, and a value date within 7 days of the
   transaction date all agree, **and the match is mutually unique**: exactly one candidate credit
   for the row and exactly one candidate row for the credit. These matches are marked
   `LOW_CONFIDENCE`. The rule depends only on the data, never on which partition runs first, so
   results do not depend on scheduling. If the match is ambiguous, the engine does not guess (the
   scale run measured one such collision; see [BENCHMARKS.md](BENCHMARKS.md#ground-truth-at-scale)).

`asOf` is the latest bank statement date received. An empty statement still proves that nothing
arrived that day, so `asOf` is not the latest credit date. This distinction was a real bug, found
by the ground-truth test: payments on the last day were being reported as "still awaiting credit".

## Classification and precedence

The classifier (`engine/Classifier.java`) is a pure function. It collects **every** finding for a
payment, then picks the primary one by precedence; the rest are stored as secondary findings.

| Rank | Class / reason | Why it ranks here |
|---:|---|---|
| 1 | `MISSING_IN_LEDGER` (no callback, or ledger never reached SUCCESS) | The customer paid but the merchant does not know; nothing else about the payment can be trusted |
| 2 | `MISSING_IN_GATEWAY` | The ledger says paid but the gateway never settled it; there is nothing to compare against the bank |
| 3 | `DUPLICATE` / `DUPLICATE_BANK_CREDIT` | Money credited twice; real money effect |
| 4 | `AMOUNT_MISMATCH` / `BEYOND_TOLERANCE` (or currency mismatch) | Real money difference |
| 5 | `MISSING_IN_BANK` / `NOT_CREDITED` (grace window expired) | Settlement never arrived |
| 6 | `MISSING_IN_BANK` / `AWAITING_CREDIT`, `LATE_SETTLEMENT` / `SETTLED_AFTER_GRACE` | Timing problems |
| 7 | `AMOUNT_MISMATCH` / `WITHIN_TOLERANCE`, `LATE_SETTLEMENT` / `SETTLED_WITHIN_GRACE` | Handled by auto-fix |
| 8 | `DUPLICATE` / `DUPLICATE_CALLBACK` | Already absorbed by dedupe; no money effect |
| 9 | `MATCHED` (`CLEAN`, `OUT_OF_ORDER_CALLBACK`, `LOW_CONFIDENCE_FALLBACK`) | |

Ties break by larger amount at risk, then by reason name. `ORPHAN_BANK_CREDIT` applies to bank
credits, not payments: a credit whose UTR matches no gateway row (`UNKNOWN_UTR`), or a UTR-less
credit that no payment claims uniquely (`NO_REFERENCE`). `ClassifierTest` has one table row per
class and reason, the boundaries (tolerance ± 1 paisa, T+2 vs T+3, last day of grace) and the
precedence rows.

**Amount at risk** per exception: the gross amount for missing-in-ledger, the ledger amount for
missing-in-gateway, the expected net amount for missing-in-bank, the extra credits for a duplicate
credit, `|difference|` for amount mismatches, the credit amount for an orphan, and 0 for timing-only
or dedupe cases.

## Auto-fix rules

Each rule is configurable (`recon.auto-fix.*`), and each produces audit entries.

| Situation | Rule | Queue status |
|---|---|---|
| Duplicate callback | dedupe; no money effect | `AUTO_RESOLVED` (`DEDUPED_NO_MONEY_EFFECT`) |
| Credit missing, grace window (T+5) not over | re-checked on every run | `PENDING_EXPECTED`; becomes `OPEN` (audit `ESCALATED`) when the window expires |
| Credit arrived T+3..T+5 | settled within grace | `AUTO_RESOLVED` (`SETTLED_WITHIN_GRACE`) |
| Fee/rounding difference ≤ ₹1.00 (`tolerance-minor: 100`) | signed adjustment journal entry | ≤ ₹0.50: `POSTED`, case `AUTO_RESOLVED`. Above ₹0.50 (`approval-threshold-minor: 50`): `PENDING_APPROVAL`, case stays `OPEN` until an `OPS_SUPERVISOR` approves |
| Everything else | — | `OPEN` → `IN_REVIEW` (assign) → `RESOLVED` (with a resolution reason) |

The engine never overwrites a human decision. `IN_REVIEW` and `RESOLVED` survive re-runs; this is
tested in `ApiIT`. If a problem disappears (for example, a late credit finally arrives), the old
exception is closed as `SUPERSEDED`, and a new exception is opened if a different problem now
applies.

## Money at risk

**Money at risk** is the sum of `amount_at_risk_minor` over exceptions in `OPEN` or `IN_REVIEW`.
`PENDING_EXPECTED` credits are reported separately as "expected in transit", because they are not
yet due; counting them would overstate the exposure. The dashboard also shows the figure by class,
aging buckets (0–1d, 2–3d, 4–7d, 8d+, by age of the business date relative to the reference date),
and a 14-day trend per business date. `ApiIT` checks that by-class, aging and trend all sum to the
headline figure.

## Idempotency

| Path | Mechanism |
|---|---|
| Callback consumer | Every event id goes into `processed_event` with the Kafka position that was applied (`INSERT … ON CONFLICT DO NOTHING RETURNING`). Re-reading the **same** position (restart, offset reset) is a no-op. A known event id at a **different** position is a gateway-side duplicate: it is recorded once in `duplicate_delivery`, keyed by position so that redelivering the duplicate is also a no-op, and the counter goes up. Status only moves forward (PENDING → SUCCESS), and an older event never overwrites a newer one; it is counted as out-of-order. The whole batch is one DB transaction, and offsets are committed after it. `CallbackConsumerIT` replays the topic from offset 0 and asserts that nothing changes |
| File ingestion | A file already ingested with the same checksum is skipped. Rows are inserted with `ON CONFLICT DO NOTHING` on their natural key, so a re-sent file only adds its new lines (`IngestionIT`) |
| Reconciliation run | Each partition replaces its `recon_result` rows and syncs its exceptions in one transaction. When the outcome is unchanged, the sync only updates `last_run_id`: no new rows, no status changes, no audit entries, no events. `ReconEngineIT` asserts the same digest, the same queue snapshot and only +2 audit rows (run started and run completed) on a re-run |

## Concurrency and determinism

A run lists the `(business date, merchant)` partitions in the range and submits them to a fixed
thread pool of `parallelism` platform threads. The value is configurable from 1 to 64 per run
(`recon.parallelism`, default 4). Each partition reads with indexed queries, classifies in memory,
and writes **only its own rows** in its own transaction. The fallback rule is a mutual-uniqueness
test over the data, so no partition's result depends on another partition having run first. Each
partition hashes its sorted outcomes, and the run digest is the SHA-256 of the partition digests in
partition order. Equal digests mean equal results. Measured: identical digests at parallelism 1 and
8 in `ReconEngineIT`, which also compares the full result table and queue, and at 1.4M entities in
the scale run.

The Kafka listener and Tomcat use virtual threads (`spring.threads.virtual.enabled`). The engine
deliberately uses a bounded platform-thread pool, because its work is JDBC-bound and limited by the
connection pool. Virtual threads would not add throughput there, and a fixed pool makes
`parallelism` mean exactly "this many partitions at once".

Only one run executes at a time per instance (an in-process lock; a second request gets `409
RUN_IN_PROGRESS`). Running several recon-service instances would need a database advisory lock;
that is not implemented.

## Security

Login returns an HS256 JWT signed with `JWT_SECRET`. Its `roles` claim maps to `ROLE_*`
authorities, and endpoints use `@PreAuthorize`:

| Action | OPS_ANALYST | OPS_SUPERVISOR | ADMIN |
|---|:-:|:-:|:-:|
| Read dashboard, exceptions, detail, SOAP bank check | ✓ | ✓ | ✓ |
| Assign, comment, resolve | ✓ | ✓ | ✓ |
| Approve adjustment above threshold | | ✓ | ✓ |
| Run reconciliation, read audit log, ground-truth evaluation | | ✓ | ✓ |
| Simulator control, reset | | | ✓ |

Every error, including 401 and 403 from the security filter chain, is RFC 7807 `problem+json` with
a stable `code` field. Error codes are listed in [API.md](API.md).

## The simulator and ground truth

`WorldGenerator` derives one RNG stream per business date from `(seed, epochDay)`. A day can
therefore be regenerated on its own and in O(N) memory, and the gateway simulator and the bank
simulator regenerate the same world independently. Every payment draws the same random values in
the same order whatever fault it gets, so changing a fault rate changes which payments are faulty
but not their amounts or timestamps. IDs that the engine must not read meaning into (UTRs, bank
refs, event ids) are opaque hashes. Each payment gets at most one fault, so the ground truth is a
clean `entity → fault` map. Multi-fault precedence is covered by the unit tests instead.

Two fault types were added beyond the original list, so that every exception class and the
low-confidence path are exercised end to end: `MISSING_GATEWAY_RECORD` (exercises
`MISSING_IN_GATEWAY`) and `BANK_UTR_MISSING` (exercises the fallback). The evaluator maps each
outcome back to a fault (`FaultType.fromOutcome`). Any outcome that maps to no fault, such as a
credit still awaiting payment, counts against precision.

## Trade-offs

- **One UTR per payment.** Real gateways usually settle in batches (one UTR for many payments, net
  of fees and refunds). Per-payment UTRs keep the matching keys simple. Batch settlement would need
  a many-to-one matching step.
- **Same business date across sources.** The ledger and gateway rows of one payment are assumed to
  carry the same business date, and a partition only looks within its own date. A payment that
  crosses midnight differently in two systems would surface as missing on both sides.
- **No refunds or chargebacks.** Only captures are modelled.
- **Synchronous run API.** `POST /api/recon/runs` blocks until the run finishes: 17–38 s at 1.4M
  entities, and under a second at demo scale. A production system would run asynchronously and
  poll.
- **Bank-sim keeps its UTR index in memory** (963 MiB resident at 1.4M credits). It is rebuilt from
  S3 on restart.
- **The audit log is kept through demo resets.** Exception ids continue across resets (`TRUNCATE`
  without `RESTART IDENTITY`), so old audit rows never point at new exceptions.

## What I would do next

1. Make runs asynchronous with progress events, and take a Postgres advisory lock per partition so
   several instances can share the work.
2. Use `COPY` for ingestion and for `recon_result` writes, and replace the full
   delete-and-insert with a diff. A re-run of unchanged data should be nearly free; today it costs
   34 s at 1.4M entities.
3. Model batch settlements (one UTR, many payments, net of fees and refunds) with a bin-matching
   step, plus refunds and chargebacks.
4. Resolve ambiguous fallback candidates by solving an assignment over date distance, and send them
   to the queue as low-confidence *suggestions* rather than leaving both sides unmatched.
5. Replace the dev-grade JWT setup with an OIDC provider, refresh tokens, and per-user audit of
   reads.
6. Run the benchmark in CI on Linux hardware and track the numbers over time.
