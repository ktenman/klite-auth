#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

NET=authbench
APP=authbench-app
CPUS=${CPUS:-2}
now() { perl -MTime::HiRes=time -e 'printf "%.1f", time'; }
since() { awk -v s="$1" -v n="$(now)" 'BEGIN { printf "%.1f", n - s }'; }
in_client() { docker exec authbench-client "$@"; }

cleanup() { docker rm -f "$APP" authbench-redis authbench-client >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup
docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET" >/dev/null
docker image inspect authbench-client >/dev/null 2>&1 ||
  printf 'FROM alpine:3.22\nRUN apk add --no-cache curl apache2-utils python3\n' | docker build -q -t authbench-client - >/dev/null

if [ -z "${IMAGE:-}" ]; then
  IMAGE=authbench-app
  docker build -q -t "$IMAGE" . >/dev/null
fi

docker run -d --name authbench-redis --network "$NET" --network-alias redis redis:8-alpine >/dev/null
docker run -d --name authbench-client --network "$NET" -v "$PWD/bench:/bench:ro" authbench-client python3 /bench/google.py >/dev/null
until in_client curl -sf -o /dev/null http://localhost:9000/userinfo; do sleep 0.2; done

oauth=http://authbench-client:9000
started=$(now)
docker run -d --name "$APP" --network "$NET" --cpus="$CPUS" \
  -e GOOGLE_CLIENT_ID=bench -e GOOGLE_CLIENT_SECRET=bench \
  -e REDIRECT_URI=https://fov.ee -e ALLOWED_EMAILS=bench@example.com \
  -e GOOGLE_AUTHORIZATION_URI=$oauth/authorize -e GOOGLE_TOKEN_URI=$oauth/token -e GOOGLE_USER_INFO_URI=$oauth/userinfo \
  -e SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_AUTHORIZATION_URI=$oauth/authorize \
  -e SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_TOKEN_URI=$oauth/token \
  -e SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_GOOGLE_USER_INFO_URI=$oauth/userinfo \
  "$IMAGE" >/dev/null

until in_client curl -sf -o /dev/null http://$APP:8083/actuator/health; do
  if [ "$(docker inspect -f '{{.State.Running}}' "$APP")" != true ] || [ "$(since "$started" | cut -d. -f1)" -ge 120 ]; then
    echo "app did not become healthy"; docker logs --tail 80 "$APP"; exit 1
  fi
  sleep 0.5
done
echo "startup_s: $(since "$started")"

in_client sh /bench/workload.sh

sleep 5
echo "anon_mib: $(docker exec "$APP" cat /sys/fs/cgroup/memory.stat | awk '$1 == "anon" { printf "%.1f", $2 / 1048576 }')"
if docker logs "$APP" 2>&1 | grep ' ERROR '; then echo "app logged errors"; exit 1; fi
