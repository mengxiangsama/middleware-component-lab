#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    printf 'Usage: sh %s /path/to/extracted-opensearch\n' "$0" >&2
    exit 2
fi
opensearch_home=$(CDPATH= cd -- "$1" && pwd)
runtime=$(mktemp -d "${TMPDIR:-/tmp}/opensearch-pit.XXXXXX")
pid=
cleanup() {
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
    fi
    rm -rf -- "$runtime"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

cp -R "$opensearch_home/config" "$runtime/config"
OPENSEARCH_PATH_CONF="$runtime/config" OPENSEARCH_JAVA_OPTS='-Xms512m -Xmx512m' \
    "$opensearch_home/bin/opensearch" \
    -Ecluster.name=pit-pagination-lab \
    -Ediscovery.type=single-node \
    -Enetwork.host=127.0.0.1 \
    -Ehttp.port=0 \
    -Etransport.port=0 \
    -Enode.portsfile=true \
    -Enode.store.allow_mmap=false \
    "-Epath.data=$runtime/data" \
    "-Epath.logs=$runtime/logs" >"$runtime/startup.log" 2>&1 &
pid=$!

remaining=120
while [ ! -s "$runtime/logs/http.ports" ]; do
    if ! kill -0 "$pid" 2>/dev/null || [ "$remaining" -eq 0 ]; then
        tail -n 40 "$runtime/startup.log" >&2
        exit 1
    fi
    remaining=$((remaining - 1))
    sleep 1
done
IFS= read -r endpoint < "$runtime/logs/http.ports"
case "$endpoint" in
    127.0.0.1:*) ;;
    *) printf 'Unexpected HTTP listener: %s\n' "$endpoint" >&2; exit 1 ;;
esac
if ! python3 "$(dirname "$0")/verify.py" "${endpoint##*:}"; then
    tail -n 40 "$runtime/startup.log" >&2
    exit 1
fi
