#!/bin/sh
set -eu

cd "$(dirname "$0")"
image=ghcr.io/open-feature/flagd:v0.19.0
container=$(docker run -d --rm --publish 127.0.0.1::8013 \
    --mount "type=bind,source=$(pwd)/flags.json,target=/data/flags.json,readonly" \
    "$image" start --uri file:/data/flags.json)
trap 'docker stop "$container" >/dev/null 2>&1 || true' EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM

address=$(docker port "$container" 8013/tcp)
case "$address" in
    127.0.0.1:*) port=${address##*:} ;;
    *) echo "Expected a loopback port, got $address" >&2; exit 1 ;;
esac
if ! go run -mod=readonly . "$port"; then
    docker logs "$container" >&2
    exit 1
fi
