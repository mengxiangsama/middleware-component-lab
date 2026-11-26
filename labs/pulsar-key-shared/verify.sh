#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
image=apachepulsar/pulsar:4.0.14
port=${PULSAR_LAB_PORT:-16650}
broker=$(docker run --rm --detach \
    --publish "127.0.0.1:$port:$port" \
    --env "PULSAR_PREFIX_brokerServicePort=$port" \
    --env 'PULSAR_MEM=-Xms256m -Xmx512m -XX:MaxDirectMemorySize=512m' \
    --entrypoint /bin/bash "$image" -c \
    'bin/apply-config-from-env.py conf/standalone.conf && exec bin/pulsar standalone --advertised-address 127.0.0.1 --no-functions-worker')
trap 'docker stop "$broker" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

attempt=0
until docker exec "$broker" curl -fsS --max-time 2 http://127.0.0.1:8080/admin/v2/brokers/health >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ] || [ "$(docker inspect -f '{{.State.Running}}' "$broker" 2>/dev/null)" != true ]; then
        echo "The Pulsar standalone broker did not become healthy." >&2
        docker logs --tail 60 "$broker" >&2 || true
        exit 1
    fi
    sleep 1
done

export PULSAR_LAB_URL="pulsar://127.0.0.1:$port"
cd "$root"
./mvnw -B -f labs/pulsar-key-shared/pom.xml clean verify
