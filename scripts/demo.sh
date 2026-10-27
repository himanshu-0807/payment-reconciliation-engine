#!/bin/sh
# One-command demo: start the whole stack, generate 14 days of payments with injected faults,
# reconcile, and print what the dashboard will show.
#   scripts/demo.sh                 (defaults: 14 days x 2000 payments/day, seed 42)
#   DAYS=7 PER_DAY=5000 SEED=1 scripts/demo.sh
set -eu
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
[ -f "$ROOT_DIR/.env" ] || { cp "$ROOT_DIR/.env.example" "$ROOT_DIR/.env"; echo "created .env from .env.example"; }
. "$ROOT_DIR/scripts/common.sh"

DAYS="${DAYS:-14}"; PER_DAY="${PER_DAY:-2000}"; SEED="${SEED:-42}"

if [ "${SKIP_COMPOSE:-0}" != "1" ]; then
  log "building and starting the stack (docker compose up -d --build)"
  docker compose -f "$ROOT_DIR/docker-compose.yml" up -d --build
fi
log "waiting for recon-service and the dashboard"
wait_for_http "$API/actuator/health/readiness" 240
wait_for_http "${WEB_URL:-http://localhost:3000}/healthz" 120 || true

ADMIN=$(login admin "$DEMO_ADMIN_PASSWORD")
log "simulating $DAYS days x $PER_DAY payments (seed $SEED); business dates end yesterday, bank statements stop today"
SIM_ID=$(api POST /api/simulator/runs "$ADMIN" \
  "$(jq -nc --argjson s "$SEED" --argjson d "$DAYS" --argjson p "$PER_DAY" '{seed:$s,days:$d,paymentsPerDay:$p,resetData:true}')" \
  | jq -r .simRunId)
SIM=$(wait_sim "$ADMIN" "$SIM_ID")
printf '%s' "$SIM" | jq -r '"simulation \(.simRunId): \(.state), \(.paymentsGenerated) payments, \(.callbacksPublished) callbacks, injected: \(.injectedFaults)"'

log "reconciling (waits for the ledger consumer to catch up)"
RUN=$(api POST /api/recon/runs "$ADMIN" '{"waitForLedgerSeconds":120}')
printf '%s' "$RUN" | jq -r '"run #\(.runId) \(.status): \(.entities) entities, \(.partitions) partitions, \(.durationMs) ms\nby class: \(.byClass)"'

api GET /api/dashboard/summary "$ADMIN" | jq -r '
  "money at risk: \(.moneyAtRiskMinor / 100) INR across \(.openCount) open exceptions",
  "expected in transit (inside grace window): \(.expectedInTransitMinor / 100) INR (\(.pendingExpectedCount))",
  "aging: \([.aging[] | "\(.key)=\(.count)"] | join(", "))",
  "adjustments awaiting supervisor approval: \(.adjustmentsPendingApproval)"'

cat <<MSG

Dashboard:  ${WEB_URL:-http://localhost:3000}   (users: analyst / supervisor / admin, passwords in .env)
Swagger UI: $API/swagger-ui.html
Note: the newest business dates are still inside the settlement grace window, so some credits are
"expected in transit" rather than at risk. For an exact ground-truth comparison run scripts/ground-truth.sh.
MSG
