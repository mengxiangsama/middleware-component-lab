#!/bin/sh
set -eu

lab_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
project="fluent-bit-loki-$(date +%s)-$$"

compose() {
    docker compose -f "$lab_dir/compose.yaml" -p "$project" "$@"
}

cleanup() {
    compose down --volumes
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

compose up -d loki
loki_url="http://$(compose port loki 3100)"

attempt=0
until curl -fsS --max-time 2 "$loki_url/ready" >/dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
        compose logs loki >&2
        printf '%s\n' 'Loki did not become ready.' >&2
        exit 1
    fi
    sleep 1
done

compose run --rm -T --no-deps fluent-bit

query_loki() {
    curl -fsSG --max-time 5 "$loki_url/loki/api/v1/query_range" \
        --data-urlencode "query=$1" --data-urlencode since=5m \
        --data-urlencode direction=forward
}

selector='{service_name="orders",environment="lab"}'
attempt=0
while :; do
    response=$(query_loki "$selector")
    if printf '%s\n' "$response" | jq -e '
        .status == "success" and ([.data.result[].values[]] | length == 4)
    ' >/dev/null; then
        break
    fi
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 15 ]; then
        printf '%s\n' 'Expected four events in Loki.' "$response" >&2
        exit 1
    fi
    sleep 1
done

entries=$(printf '%s\n' "$response" | jq -c '
    [.data.result[].values[]] | sort_by(.[0], .[1]) | map(.[1])
')
if ! printf '%s\n' "$entries" | jq -e '
    map(rtrimstr("\n") | split("\n") | length) == [1, 7, 3, 1]
' >/dev/null; then
    printf '%s\n' 'Unexpected Loki event boundaries.' "$entries" >&2
    exit 1
fi
reconstructed=$(printf '%s\n' "$entries" | jq -r 'join("")')
if [ "$reconstructed" != "$(cat "$lab_dir/orders.txt")" ]; then
    printf '%s\n' 'Loki log lines differ from the input file.' >&2
    exit 1
fi

response=$(query_loki "$selector |= \"checkout failed\"")
printf '%s\n' "$response" | jq -er '
    [.data.result[].values[][1]]
    | if length == 1 and (.[0] | contains("Caused by: java.net.SocketTimeoutException"))
      then .[0]
      else error("Expected one checkout error with the original cause")
      end
'
printf '%s\n' 'Verified four Loki events and one matching checkout error.'
