#!/bin/sh
# Health of every component: container state, Spring readiness, Kafka, S3 bucket, dashboard.
# Exit code 0 only if everything is healthy.
set -u
ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
[ -f "$ROOT_DIR/.env" ] && { set -a; . "$ROOT_DIR/.env"; set +a; }
cd "$ROOT_DIR" || exit 1   # run compose from the repo root (paths may contain spaces)
fail=0
check() { # NAME COMMAND...
  _n="$1"; shift
  if "$@" >/dev/null 2>&1; then printf '  %-28s OK\n' "$_n"; else printf '  %-28s FAIL\n' "$_n"; fail=1; fi
}
echo "containers:"
docker compose ps --format '{{.Service}} {{.State}} {{.Health}}' | awk '{ s = ($3 == "" ? $2 : $3); printf "  %-28s %s\n", $1, s; if (s != "healthy" && s != "running") bad=1 } END { exit bad }' || fail=1
echo "endpoints:"
for svc in recon-service:8080 gateway-sim:8081 bank-sim:8082; do
  check "${svc%%:*} readiness" sh -c "curl -fsS http://localhost:${svc##*:}/actuator/health/readiness | grep -q UP"
done
check "bank SOAP WSDL" sh -c "curl -fsS http://localhost:8082/ws/settlement.wsdl | grep -q getSettlementStatus"
check "dashboard (nginx)" curl -fsS http://localhost:3000/healthz
check "kafka topics" sh -c "docker compose exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q gateway.callbacks"
check "s3 bucket ${S3_BUCKET:-recon-data}" sh -c "docker compose exec -T localstack awslocal s3 ls s3://${S3_BUCKET:-recon-data}"
check "postgres" docker compose exec -T postgres pg_isready -U "${POSTGRES_USER:-recon}"
exit $fail
