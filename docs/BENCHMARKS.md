# Benchmarks

Every number on this page comes from a run that was actually executed. The raw output is committed
next to this file in [`docs/benchmarks/`](benchmarks/), and each section gives the command that
reproduces it. Nothing here was estimated or extrapolated.

## Environment

| | |
|---|---|
| Machine | Apple Silicon Mac (arm64), 10 CPU cores, 16 GB RAM |
| Docker | Docker Desktop 29.8, Linux VM with 10 CPUs and 7.7 GiB of memory |
| JVM | OpenJDK 21.0.10 |
| Postgres | 16 (container `postgres:16-alpine`, `shared_buffers=256MB`) |
| Kafka | 3.9.1, single node, KRaft, 6 partitions on `gateway.callbacks` |
| S3 | LocalStack 3.8.1 (Community) |
| Code | working tree at `043e835` plus the V3 index migration and the results-only reset, committed right after the run as `e05fff9` (the JSON records `043e835`, the HEAD at run time) |

For the scale run, the three Spring Boot services ran as **host JVMs** (`java -Xmx2g` for
recon-service, `-Xmx1g` for the two simulators). Postgres, Kafka and LocalStack ran in Docker. This
was not a run with every service in containers.

## Scale run: 14 days × 100,000 payments/day

```sh
# infra up (docker compose up -d postgres kafka localstack), services started as jars, then:
scripts/benchmark.sh 14 100000 20260901
```

The run goes through the real system. gateway-sim publishes the callbacks to Kafka and writes
settlement CSVs to LocalStack S3. bank-sim writes the statements. recon-service consumes the
callbacks, ingests the files over S3, reconciles and is scored against the ground truth. Raw output:
[`benchmarks/scale-14x100k-20261027.json`](benchmarks/scale-14x100k-20261027.json).

### Volume

| | count |
|---|---:|
| Payments generated | 1,400,000 |
| Callbacks published to Kafka (with duplicates and out-of-order) | 1,420,828 |
| Gateway settlement rows (14 files) | 1,397,199 |
| Bank statement rows (19 files) | 1,400,060 |
| Rows ingested from S3 | 2,797,259 |
| Entities reconciled (payments + unmatched bank credits) | 1,402,802 |
| Partitions (business date × merchant) | 95 |
| Faults injected | 78,309 |

### Timings

| Step | Time | Notes |
|---|---:|---|
| Simulation (generate + publish + write files + bank statements) | 12.4 s | gateway-sim and bank-sim |
| Ledger caught up after the simulation finished | 10.4 s | Kafka consumer lag reached 0 |
| Simulation start → ledger caught up | 22.8 s | ≈ 62,400 callbacks/s end to end |
| Ingestion of 33 files from S3 (parallelism 1) | 43.1 s | ≈ 65,000 rows/s |
| **Reconcile at parallelism 1** | **38.3 s** | 1,402,802 entities |
| **Reconcile at parallelism 8** (same data, results cleared first) | **16.9 s** | 2.27× faster than parallelism 1 |
| Idempotent re-run at parallelism 8 (no reset) | 34.0 s | 0 new exceptions |
| Ground-truth evaluation | 1.3 s | |

The **result digest** (SHA-256 over every outcome, in partition order) was
`921b9d49…74b13fc4` at parallelism 1, at parallelism 8 and on the re-run. That proves the results
are identical regardless of parallelism, at 1.4M entities.

### Ground truth at scale

| | |
|---|---:|
| Precision | 0.999949 |
| Recall | 0.999974 |
| Mismatched entities | 4 of 1,402,802 |

All four mismatches have a single cause. Two different payments of merchant M005, 4 days apart,
both had their UTR stripped from the bank narration (`BANK_UTR_MISSING`), and both had exactly the
same net amount, ₹2,177.82. The amount + merchant + date-window fallback only matches when the pair
is **mutually unique**: one candidate credit for the payment, and one candidate payment for the
credit. Here each credit had two candidate payments, so the engine refused to guess. It reported
both payments as `MISSING_IN_BANK` and both credits as `ORPHAN_BANK_CREDIT`, and all four go to the
ops queue. This is intended: a wrong automatic match would hide money, while an ambiguous one costs
an analyst a minute. Those four items are the only reason the scale-run figures are below 100%.
Ground-truth runs at smaller volumes, listed below, hit 100%, because amount collisions inside a
7-day window are rare.

### Peak memory (sampled every second)

| Process | Peak |
|---|---:|
| recon-service, JVM resident set | 941 MiB |
| recon-service, heap used (actuator `jvm.memory.used`, area=heap) | 767 MiB |
| gateway-sim, JVM resident set | 676 MiB |
| bank-sim, JVM resident set (holds a 1.4M-entry UTR index for SOAP) | 963 MiB |
| Postgres container | 771 MiB |
| Kafka container | 855 MiB |
| LocalStack container | 472 MiB |

Memory is sampled, so short peaks between two samples may be missed.

### Why parallelism 8 is "only" 2.27× faster

Each partition does a few indexed reads and then writes its own rows: it deletes and re-inserts
about 15,000 `recon_result` rows, syncs exceptions and appends audit entries. All partitions write
to one Postgres instance, so with 8 workers the database (WAL, index maintenance) becomes the
bottleneck instead of the Java side. The re-run is slower than the first parallelism-8 run (34.0 s
vs 16.9 s) because it starts from full tables. It deletes 1.4M result rows before re-inserting them,
and it updates `last_run_id` on every existing exception. The first run started from truncated
tables. Ideas for reducing both are in [DESIGN.md](DESIGN.md#what-i-would-do-next).

## Smaller runs

| Run | Command | Entities | Faults | Precision | Recall | Recon time |
|---|---|---:|---:|---:|---:|---:|
| Full docker compose stack, 7 × 20k | `scripts/ground-truth.sh 7 20000 42` | 140,280 | 7,762 | 1.0 | 1.0 | 2,895 ms (p=8) |
| Kubernetes (minikube), 3 × 5k | `API=… scripts/ground-truth.sh 3 5000 7` | 15,030 | 856 | 1.0 | 1.0 | 406 ms |
| Dashboard simulator panel, 14 × 2k | UI, default rates | 28,056 | 1,590 | 1.0 | 1.0 | 452 ms |
| Integration test (Testcontainers), 3 × 4k, doubled rates | `mvn verify` (`ReconEngineIT`) | 12,048 | 1,382 | 1.0 | 1.0 | ~1 s |
| Smoke benchmark, 2 × 20k | `scripts/benchmark.sh 2 20000 7` | 40,080 | 2,194 | 1.0 | 1.0 | 969 ms (p=1) / 398 ms (p=8) |

Raw output: [`benchmarks/ground-truth-7x20k-compose-20261027.txt`](benchmarks/ground-truth-7x20k-compose-20261027.txt),
[`benchmarks/k8s-minikube-pods-20261027.txt`](benchmarks/k8s-minikube-pods-20261027.txt),
[`benchmarks/smoke-2x20k-20261027.json`](benchmarks/smoke-2x20k-20261027.json).

## Docker images

Built with `docker compose build` from the multi-stage Dockerfiles:

| Image | Size |
|---|---:|
| `recon/recon-service:local` | 472 MB |
| `recon/gateway-sim:local` | 390 MB |
| `recon/bank-sim:local` | 351 MB |
| `recon/web:local` (nginx + static build) | 76.7 MB |
