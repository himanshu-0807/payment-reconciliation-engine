#!/bin/sh
# Shared helpers for the demo / benchmark / health scripts. POSIX sh; needs curl and jq.
set -eu

API="${API:-http://localhost:8080}"
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if [ -f "$ROOT_DIR/.env" ]; then
  # shellcheck disable=SC1091
  set -a; . "$ROOT_DIR/.env"; set +a
fi

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }; }
need curl; need jq

log() { printf '%s %s\n' "$(date '+%H:%M:%S')" "$*" >&2; }
now_ms() { python3 -c 'import time;print(int(time.time()*1000))' 2>/dev/null || echo $(( $(date +%s) * 1000 )); }

# login USER PASSWORD -> prints JWT
login() {
  curl -fsS -X POST "$API/api/auth/login" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg u "$1" --arg p "$2" '{username:$u,password:$p}')" | jq -r .token
}

# api METHOD PATH TOKEN [JSON] -> prints body; fails on HTTP >= 400
api() {
  _m="$1"; _p="$2"; _t="$3"; _b="${4:-}"
  if [ -n "$_b" ]; then
    curl -fsS --max-time 3600 -X "$_m" "$API$_p" -H "Authorization: Bearer $_t" -H 'Content-Type: application/json' -d "$_b"
  else
    curl -fsS --max-time 3600 -X "$_m" "$API$_p" -H "Authorization: Bearer $_t"
  fi
}

wait_for_http() { # URL TIMEOUT_SECONDS
  _i=0
  until curl -fsS "$1" >/dev/null 2>&1; do
    _i=$((_i + 1)); [ "$_i" -ge "$2" ] && { echo "timed out waiting for $1" >&2; return 1; }
    sleep 1
  done
}

# wait_sim TOKEN SIM_RUN_ID -> waits until the simulation leaves RUNNING; prints final status JSON
wait_sim() {
  while :; do
    _s=$(api GET "/api/simulator/runs/$2" "$1")
    _state=$(printf '%s' "$_s" | jq -r .state)
    [ "$_state" != "RUNNING" ] && { printf '%s' "$_s"; return 0; }
    log "  simulation: day $(printf '%s' "$_s" | jq -r .daysCompleted)/$(printf '%s' "$_s" | jq -r .config.days)"
    sleep 2
  done
}

# Sum of lag of the ledger consumer group (via the Kafka container's CLI).
ledger_lag() {
  docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 --describe --group recon-ledger 2>/dev/null \
    | awk '$1=="recon-ledger" && $6 ~ /^[0-9]+$/ {s+=$6} END {print s+0}'
}
