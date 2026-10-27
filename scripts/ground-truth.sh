#!/bin/sh
# Ground-truth check against the running stack: simulate with every bank statement delivered (no
# cutoff, so the grace window has expired for every business date), reconcile, and print precision
# and recall per fault type.
#   scripts/ground-truth.sh [DAYS] [PER_DAY] [SEED]     (defaults: 7 20000 42)
. "$(dirname -- "$0")/common.sh"
DAYS="${1:-7}"; PER_DAY="${2:-20000}"; SEED="${3:-42}"
ADMIN=$(login admin "$DEMO_ADMIN_PASSWORD")
ID=$(api POST /api/simulator/runs "$ADMIN" "$(jq -nc --argjson s "$SEED" --argjson d "$DAYS" --argjson p "$PER_DAY" \
  '{seed:$s,days:$d,paymentsPerDay:$p,startDate:"2026-08-01",bankCutoffDate:"2099-12-31",resetData:true}')" | jq -r .simRunId)
wait_sim "$ADMIN" "$ID" >/dev/null
api POST /api/recon/runs "$ADMIN" '{"parallelism":8,"waitForLedgerSeconds":300}' \
  | jq -c '{runId,entities,partitions,durationMs,digest}'
api GET "/api/ground-truth/evaluation?simRunId=$ID" "$ADMIN" | jq -r '
  "sim \(.simRunId): \(.entitiesEvaluated) entities, \(.injectedFaults) injected faults",
  "overall precision \(.precision)  recall \(.recall)  unmapped outcomes \(.unmappedOutcomes)",
  (.byFault | to_entries[] | "  \(.key)\tinjected=\(.value.injected)\tdetected=\(.value.detected)\tprecision=\(.value.precision)\trecall=\(.value.recall)"),
  (if (.sampleMismatches|length) > 0 then "mismatches: \(.sampleMismatches)" else "no mismatches" end)'
