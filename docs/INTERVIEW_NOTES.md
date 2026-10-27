# Interview notes

Ten questions this project is likely to prompt, with answers based on what was actually built and
measured.

### 1. How do you know the engine is correct?

The simulator injects known faults from a seed and writes a ground-truth file mapping each faulty
payment or credit to its fault. `GroundTruthEvaluator` maps every engine outcome back to a fault
type and computes precision and recall per fault. On the full compose stack, with 7 days × 20,000
payments and 7,762 injected faults, both are 1.0. At 14 × 100,000 they are 0.999949 and 0.999974.
The only gap is four entities from one ambiguous amount collision, which the engine reports instead
of guessing. That test also caught a real bug: "as of" was taken from the latest credit instead of
the latest statement, so the last day's missing credits never escalated.

### 2. How is callback handling idempotent?

Each callback has a stable event id. In one transaction per batch, the consumer inserts the ids into
`processed_event` with `ON CONFLICT DO NOTHING RETURNING`, storing the Kafka partition and offset of
the delivery it applied. Re-reading that same position, after a restart or offset reset, is a no-op.
A known id at a *different* position is a gateway duplicate: it goes into `duplicate_delivery`,
keyed by position, so that even redelivering the duplicate counts once. The test replays the whole
topic from offset 0 through the processor and asserts the ledger is unchanged.

### 3. What happens when callbacks arrive out of order?

The ledger state only moves forward (PENDING → SUCCESS), and the latest event timestamp wins. An
older event never regresses the status; it increments `out_of_order_events`, so the engine can
report `MATCHED / OUT_OF_ORDER_CALLBACK`. Kafka messages are keyed by payment id, so per-payment
order within a partition is preserved, and the simulator deliberately sends SUCCESS before the
stale PENDING.

### 4. A payment has several problems. Which one do you report?

All of them are kept as findings, and one primary is picked by a fixed precedence, roughly "money
moved or can't be verified" before "late" before "already auto-fixed". The order is: missing in
ledger, missing in gateway, duplicate credit, real amount mismatch, not credited, late or awaiting,
fee mismatch or late within grace, duplicate callback, matched. Ties go to the larger amount at
risk. The classifier is a pure function with 32 table-driven test cases, including precedence rows.
One of those tests caught that the secondary findings were sorted with a different tie-break than
the one used to pick the primary.

### 5. How does the parallel run stay deterministic?

The work is split into (business date, merchant) partitions on a fixed pool of N threads. Each
partition only reads and writes its own rows in its own transaction. The fallback match is a
mutual-uniqueness rule over the data, so it never depends on another partition having run first.
Every partition hashes its sorted outcomes, and the run digest combines them in partition order.
Parallelism 1 and 8 produce the same digest: in the integration test, which also compares the full
result and queue tables, and at 1.4M entities in the benchmark.

### 6. Why is parallelism 8 only 2.27× faster than parallelism 1?

Measured: 38.3 s vs 16.9 s for 1.4M entities. The Java side scales, but every partition rewrites
around 15,000 result rows plus exceptions and audit entries in one Postgres instance, so WAL and
index maintenance become the bottleneck. The next step would be `COPY` and diff-based writes
instead of delete-and-insert. That would also fix the re-run cost, 34 s today.

### 7. How do you avoid floating point for money?

Every amount is a `BIGINT` in paise with a `CHAR(3)` currency. Fees use integer basis-point math
with explicit half-up rounding. Adjustments are signed paise. Even the React app formats money with
integer division and modulo, and its formatter rejects non-integer input; the parser turns user
input like `1,500.50` into paise by splitting the rupee and paise digits and using integer math.

### 8. What does "money at risk" mean, and what's excluded?

It is the sum of the amount at risk of exceptions in `OPEN` or `IN_REVIEW`. That amount is the
gross for missing-in-ledger, the net for missing-in-bank, the extra credits for duplicates,
`|difference|` for mismatches, and the credit amount for orphans. Credits still inside the T+5
grace window are `PENDING_EXPECTED` and are shown separately as "expected in transit". They are not
yet a loss, and counting them would overstate exposure. The API test checks that the breakdown by
class, the aging buckets and the 14-day trend all add up to the headline figure.

### 9. Which fixes are automatic, and how do you keep them safe?

Only three kinds:
- **Dedupe of duplicate callbacks.** No money effect.
- **Waiting out the grace window for late credits.** These escalate automatically when it expires.
- **Fee/rounding differences up to ₹1.00.** These become signed adjustment journal entries. Up to
  ₹0.50 is posted automatically; above that, an `OPS_SUPERVISOR` must approve, and an analyst's
  resolve is rejected with 409 until then.

Each rule can be switched off. Every engine and user action is written to an audit log that a
Postgres trigger makes append-only. Re-runs never overwrite a human decision (`IN_REVIEW` or
`RESOLVED`).

### 10. What would break first in production, and what would you change?

- The run API is synchronous, and the run lock is in-process, so it is single-instance only.
- Settlements are modelled as one UTR per payment, while real gateways settle in batches.
- The JWT setup is a single HMAC secret.

I would add asynchronous runs with a Postgres advisory lock per partition, batch-settlement
matching, refunds and chargebacks, `COPY`-based writes, and OIDC. I would also run the benchmark in
CI on Linux, because all numbers so far come from one laptop with the services as host JVMs.
