#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
export MYSQL_QUEUE_PASSWORD
MYSQL_QUEUE_PASSWORD=$(openssl rand -hex 16)
root_password=$(openssl rand -hex 16)
container=$(docker run --rm -d --publish 127.0.0.1::3306 \
    --env "MYSQL_ROOT_PASSWORD=$root_password" \
    --env MYSQL_DATABASE=shipment_lab --env MYSQL_USER=queue_lab \
    --env "MYSQL_PASSWORD=$MYSQL_QUEUE_PASSWORD" mysql:8.4.13)
trap 'docker stop "$container" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

attempt=0
until docker exec "$container" mysqladmin --host=127.0.0.1 --protocol=tcp --silent ping >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
        echo "MySQL did not start accepting TCP connections" >&2
        exit 1
    fi
    sleep 1
done

binding=$(docker port "$container" 3306/tcp)
case "$binding" in
    127.0.0.1:*) MYSQL_QUEUE_PORT=${binding##*:} ;;
    *) echo "Expected a loopback port, got $binding" >&2; exit 1 ;;
esac
export MYSQL_QUEUE_PORT

cd "$root"
./mvnw -B -f labs/mysql-skip-locked/pom.xml clean verify
