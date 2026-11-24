#!/bin/sh

set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export RABBITMQ_LAB_USER=publisher_lab
RABBITMQ_LAB_PASSWORD=$(openssl rand -hex 16)
export RABBITMQ_LAB_PASSWORD

broker=$(docker run --detach --publish 127.0.0.1::5672 \
    --env "RABBITMQ_DEFAULT_USER=$RABBITMQ_LAB_USER" \
    --env "RABBITMQ_DEFAULT_PASS=$RABBITMQ_LAB_PASSWORD" \
    rabbitmq:4.2.5)
cleanup() {
    docker rm -f -v "$broker" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

attempt=0
until docker exec --user rabbitmq "$broker" rabbitmq-diagnostics -q -t 2 check_port_connectivity >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        echo "The lab broker did not open its AMQP port." >&2
        docker logs --tail 30 "$broker" >&2
        exit 1
    fi
    sleep 1
done

binding=$(docker port "$broker" 5672/tcp)
case "$binding" in
    127.0.0.1:*) RABBITMQ_LAB_PORT=${binding##*:} ;;
    *) echo "Expected a loopback port, got: $binding" >&2; exit 1 ;;
esac
export RABBITMQ_LAB_PORT

cd "$root"
./mvnw -B -f labs/rabbitmq-publisher-confirms/pom.xml clean verify
