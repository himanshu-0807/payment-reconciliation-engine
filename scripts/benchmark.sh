#!/bin/sh
# Scale benchmark: DAYS x PER_DAY payments through the real simulators, Kafka, LocalStack S3 and the
# recon-service API. Measures simulation, ledger catch-up, ingestion, reconciliation at parallelism 1
# and 8 on the same data, an idempotent re-run, ground-truth precision/recall, and peak memory.
#
# Usage: scripts/benchmark.sh [DAYS] [PER_DAY] [SEED]      (defaults: 14 100000 20260901)
# Requires the stack to be running (docker compose up, or the jars plus compose infra).
. "$(dirname -- "$0")/common.sh"

DAYS="${1:-14}"; PER_DAY="${2:-100000}"; SEED="${3:-20260901}"
START_DATE="${START_DATE:-2026-09-01}"
OUT_DIR="$ROOT_DIR/bench-results"; mkdir -p "$OUT_DIR"
STAMP=$(date '+%Y%m%d-%H%M%S'); OUT="$OUT_DIR/bench-$STAMP.json"; MEM="$OUT_DIR/mem-$STAMP.tsv"

ADMIN=$(login admin "$DEMO_ADMIN_PASSWORD")

# ---- memory sampler: docker containers and local JVMs, plus recon-service heap via actuator
sample_memory() {
  while :; do
    docker stats --no-stream --format '{{.Name}}\t{{.MemUsage}}' 2>/dev/null | awk -F'\t' '{
        split($2, a, " / "); v=a[1]; u=v; gsub(/[0-9.]/, "", u); gsub(/[A-Za-z]/, "", v);
        m = (u=="GiB") ? v*1024 : (u=="MiB") ? v : (u=="KiB") ? v/1024 : v/1048576;
        printf "container:%s\t%.1f\n", $1, m }' >> "$MEM"
    for p in $(pgrep -f 'recon-service.jar|gateway-sim.jar|bank-sim.jar' 2>/dev/null); do
      n=$(ps -o command= -p "$p" | grep -oE '(recon-service|gateway-sim|bank-sim)\.jar' | head -1)
      r=$(ps -o rss= -p "$p" | tr -d ' ')
      [ -n "$r" ] && printf 'jvm-rss:%s\t%.1f\n' "$n" "$(echo "$r / 1024" | bc -l)" >> "$MEM"
    done
    h=$(curl -fsS "$API/actuator/metrics/jvm.memory.used?tag=area:heap" -H "Authorization: Bearer $ADMIN" 2>/dev/null \
        | jq -r '.measurements[0].value // empty')
    [ -n "$h" ] && printf 'recon-service-heap-used\t%.1f\n' "$(echo "$h / 1048576" | bc -l)" >> "$MEM"
    sleep 1
  done
}
sample_memory & SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true' EXIT INT TERM

log "simulating $DAYS days x $PER_DAY payments (seed $SEED, start $START_DATE)"
T0=$(now_ms)
SIM=$(api POST /api/simulator/runs "$ADMIN" "$(jq -nc --argjson s "$SEED" --arg d "$START_DATE" --argjson n "$DAYS" \
      --argjson p "$PER_DAY" '{seed:$s,startDate:$d,days:$n,paymentsPerDay:$p,bankCutoffDate:"2099-12-31",resetData:true}')")
SIM_ID=$(printf '%s' "$SIM" | jq -r .simRunId)
SIM_FINAL=$(wait_sim "$ADMIN" "$SIM_ID")
T_SIM=$(now_ms)
[ "$(printf '%s' "$SIM_FINAL" | jq -r .state)" = "COMPLETED" ] || { echo "$SIM_FINAL" >&2; exit 1; }
CALLBACKS=$(printf '%s' "$SIM_FINAL" | jq .callbacksPublished)
log "simulation done in $((T_SIM - T0)) ms; $CALLBACKS callbacks published; waiting for ledger consumer"

while [ "$(ledger_lag)" -gt 0 ]; do sleep 1; done
T_LEDGER=$(now_ms)
log "ledger caught up $((T_LEDGER - T_SIM)) ms after the simulation finished"

log "reconcile at parallelism 1 (includes ingestion of all files)"
R1=$(api POST /api/recon/runs "$ADMIN" '{"parallelism":1,"waitForLedgerSeconds":0}')
log "  $(printf '%s' "$R1" | jq -c '{durationMs,reconcileMs,entities,partitions,digest}')"

api POST "/api/admin/reset?scope=results" "$ADMIN" >/dev/null
log "reconcile at parallelism 8 on the same ingested data (results cleared first)"
R8=$(api POST /api/recon/runs "$ADMIN" '{"parallelism":8,"waitForLedgerSeconds":0}')
log "  $(printf '%s' "$R8" | jq -c '{durationMs,reconcileMs,entities,partitions,digest}')"

log "idempotent re-run at parallelism 8 (no reset)"
RR=$(api POST /api/recon/runs "$ADMIN" '{"parallelism":8,"waitForLedgerSeconds":0}')

G0=$(now_ms)
GT=$(api GET "/api/ground-truth/evaluation?simRunId=$SIM_ID" "$ADMIN")
G1=$(now_ms)

kill $SAMPLER 2>/dev/null || true
PEAKS=$(awk -F'\t' '{ if ($2+0 > m[$1]) m[$1]=$2+0 } END { for (k in m) printf "%s\t%.0f\n", k, m[k] }' "$MEM" | sort \
        | jq -Rn '[inputs | split("\t") | {(.[0]): (.[1]|tonumber)}] | add')

jq -n --arg stamp "$STAMP" --arg commit "$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || echo unknown)" \
  --arg host "$(uname -sm), $(sysctl -n hw.ncpu 2>/dev/null || nproc) cpus" \
  --argjson days "$DAYS" --argjson perDay "$PER_DAY" --argjson seed "$SEED" \
  --argjson sim "$SIM_FINAL" --argjson simMs "$((T_SIM - T0))" --argjson ledgerMs "$((T_LEDGER - T_SIM))" \
  --argjson ledgerTotalMs "$((T_LEDGER - T0))" --argjson r1 "$R1" --argjson r8 "$R8" --argjson rr "$RR" \
  --argjson gt "$GT" --argjson gtMs "$((G1 - G0))" --argjson mem "${PEAKS:-null}" '{
    stamp:$stamp, commit:$commit, host:$host, days:$days, paymentsPerDay:$perDay, seed:$seed,
    simulation:{ms:$simMs, payments:$sim.paymentsGenerated, callbacks:$sim.callbacksPublished,
                gatewayRows:$sim.gatewayRowsWritten, bankRows:$sim.bankRowsWritten},
    ledger:{catchUpAfterSimMs:$ledgerMs, simStartToCaughtUpMs:$ledgerTotalMs},
    ingestion:{ms:$r1.ingestion.durationMs, files:$r1.ingestion.filesIngested, rows:$r1.ingestion.rowsRead},
    reconP1:{totalMs:$r1.durationMs, reconcileMs:$r1.reconcileMs, entities:$r1.entities, partitions:$r1.partitions, digest:$r1.digest},
    reconP8:{totalMs:$r8.durationMs, reconcileMs:$r8.reconcileMs, entities:$r8.entities, digest:$r8.digest},
    rerunP8:{totalMs:$rr.durationMs, reconcileMs:$rr.reconcileMs, exceptionsCreated:$rr.exceptions.created, digest:$rr.digest},
    digestsIdentical: ($r1.digest == $r8.digest and $r8.digest == $rr.digest),
    groundTruth:{ms:$gtMs, precision:$gt.precision, recall:$gt.recall, injectedFaults:$gt.injectedFaults,
                 entities:$gt.entitiesEvaluated, mismatches:($gt.sampleMismatches|length)},
    peakMemoryMiB:$mem }' > "$OUT"
log "results written to $OUT"
cat "$OUT"
