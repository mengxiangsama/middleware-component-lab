#!/bin/sh
set -eu

lab_dir=$(CDPATH= cd "$(dirname "$0")" && pwd)
chart="$lab_dir/chart"
staging="$lab_dir/values-staging.yaml"

check_render() {
    replicas=$1
    port=$2
    shift 2
    manifest=$(helm template orders-lab "$chart" "$@")
    printf '%s\n' "$manifest" | grep -Ex "  replicas: $replicas"
    printf '%s\n' "$manifest" | grep -Ex "    - port: $port"
}

expect_rejection() {
    override=$1
    diagnostic=$2
    if output=$(helm template orders-lab "$chart" --set "$override" 2>&1); then
        printf 'Expected schema rejection for %s\n' "$override" >&2
        return 1
    fi
    printf '%s\n' "$output" | grep -Fq "values don't meet the specifications of the schema(s)" || {
        printf '%s\n' "$output" >&2
        return 1
    }
    printf '%s\n' "$output" | grep -F -- "$diagnostic" || {
        printf '%s\n' "$output" >&2
        return 1
    }
}

helm version --short
helm lint --strict "$chart"
helm lint --strict "$chart" -f "$staging"
check_render 1 80
check_render 2 8080 -f "$staging"

expect_rejection service.port=http "at '/service/port': got string, want integer"
expect_rejection service.port=65536 "at '/service/port': maximum:"
expect_rejection service.prt=8080 "at '/service': additional properties 'prt' not allowed"
expect_rejection replicaCount=0 "at '/replicaCount': minimum:"
