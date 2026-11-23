#!/bin/sh
set -eu

lab_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
fluent_bit_image=cr.fluentbit.io/fluent/fluent-bit:5.2.0

records=$(docker run --rm --network none \
    --mount "type=bind,src=$lab_dir,dst=/lab,readonly" \
    "$fluent_bit_image" --quiet -c /lab/fluent-bit.yaml)
printf '%s\n' "$records"

if ! printf '%s\n' "$records" | jq -se '
    map(.log | rtrimstr("\n") | split("\n") | length) == [1, 7, 3, 1]
' >/dev/null; then
    printf '%s\n' 'Expected four events with 1, 7, 3 and 1 lines.' >&2
    exit 1
fi

reconstructed=$(printf '%s\n' "$records" | jq -sr 'map(.log) | join("")')
if [ "$reconstructed" != "$(cat "$lab_dir/orders.txt")" ]; then
    printf '%s\n' 'Output log lines differ from the input file.' >&2
    exit 1
fi

printf '%s\n' 'Verified four events and all 12 input lines.' >&2
