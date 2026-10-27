#!/bin/sh
# Scans service logs with grep/awk: errors and warnings per service, ledger consumer throughput and
# duplicates absorbed, reconciliation runs, ingestion, and anything that looks like a stack trace.
#   scripts/log-check.sh            (reads `docker compose logs`)
#   LOG_DIR=logs scripts/log-check.sh   (reads logs/<service>.log written by locally started jars)
set -u
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for s in recon-service gateway-sim bank-sim; do
  if [ -n "${LOG_DIR:-}" ]; then cp "$LOG_DIR/$s.log" "$TMP/$s.log" 2>/dev/null || : > "$TMP/$s.log"
  else docker compose -f "$ROOT_DIR/docker-compose.yml" logs --no-color --no-log-prefix "$s" > "$TMP/$s.log" 2>/dev/null; fi
done

echo "== log levels per service"
for s in recon-service gateway-sim bank-sim; do
  awk -v s="$s" '$2=="ERROR"{e++} $2=="WARN"{w++} $2=="INFO"{i++} END {printf "  %-14s INFO=%d WARN=%d ERROR=%d\n", s, i, w, e}' "$TMP/$s.log"
done

echo "== ledger consumer (recon-service)"
grep 'ledger batch' "$TMP/recon-service.log" | awk '{
    for (i = 1; i <= NF; i++) if (split($i, kv, "=") == 2 && kv[2] ~ /^[0-9]+$/) f[kv[1]] += kv[2]
    n++ } END { printf "  logged batches=%d records=%d applied=%d duplicates=%d redeliveries=%d rejected=%d\n", n, f["size"], f["applied"], f["duplicates"], f["redeliveries"], f["rejected"] }'
  echo "  (batches are logged when they contain duplicates, redeliveries or rejects)"

echo "== ingestion"
grep 'ingestion: files seen' "$TMP/recon-service.log" | sed 's/.*ingestion: /  /' | tail -5

echo "== reconciliation runs"
grep -E 'recon run [0-9]+ completed' "$TMP/recon-service.log" \
  | sed -E 's/.*recon run ([0-9]+) completed: partitions=([0-9]+) entities=([0-9]+) parallelism=([0-9]+) reconcile=([0-9]+)ms total=([0-9]+)ms.*digest=(.{12}).*/  run \1: partitions=\2 entities=\3 parallelism=\4 reconcile=\5ms total=\6ms digest=\7…/' | tail -10
grep -c -E 'recon run [0-9]+ failed' "$TMP/recon-service.log" | awk '{ printf "  failed runs: %d\n", $1 }'

echo "== simulator"
grep -E 'sim .* completed in' "$TMP/gateway-sim.log" | sed -E 's/.*sim ([^ ]+) completed in ([0-9]+) ms: ([^f]*)faults=.*/  \1: \2 ms, \3/' | tail -5
grep 'bank statements for' "$TMP/bank-sim.log" | sed 's/.*bank statements/  bank statements/' | tail -5

echo "== stack traces / exceptions"
for s in recon-service gateway-sim bank-sim; do
  c=$(grep -c -E '^(\s+at |Caused by:)' "$TMP/$s.log" || true)
  printf '  %-14s %s stack-trace lines\n' "$s" "$c"
  [ "$c" -gt 0 ] && grep -m 3 -E 'Exception|Error:' "$TMP/$s.log" | cut -c1-200 | sed 's/^/      /'
done
exit 0
