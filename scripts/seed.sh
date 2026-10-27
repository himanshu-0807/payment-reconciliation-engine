#!/bin/sh
# Start a simulation with explicit parameters (the stack must be running).
#   scripts/seed.sh [DAYS] [PER_DAY] [SEED] [RATES_JSON]
#   scripts/seed.sh 3 5000 7 '{"MISSING_CALLBACK":0.02,"DUPLICATE_BANK_CREDIT":0.01}'
. "$(dirname -- "$0")/common.sh"
DAYS="${1:-3}"; PER_DAY="${2:-1000}"; SEED="${3:-42}"; RATES="${4:-null}"
ADMIN=$(login admin "$DEMO_ADMIN_PASSWORD")
BODY=$(jq -nc --argjson s "$SEED" --argjson d "$DAYS" --argjson p "$PER_DAY" --argjson r "$RATES" \
  '{seed:$s,days:$d,paymentsPerDay:$p,resetData:true} + (if $r == null then {} else {faultRates:$r} end)')
ID=$(api POST /api/simulator/runs "$ADMIN" "$BODY" | jq -r .simRunId)
wait_sim "$ADMIN" "$ID" | jq '{simRunId,state,paymentsGenerated,callbacksPublished,gatewayRowsWritten,bankRowsWritten,injectedFaults,error}'
