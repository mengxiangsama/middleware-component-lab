#!/bin/sh
set -eu

lab_dir=$(cd -- "$(dirname -- "$0")" && pwd)
prometheus_image=prom/prometheus:v3.16.0

promtool() {
    docker run --rm --network none --entrypoint /bin/promtool \
        --mount "type=bind,src=$lab_dir,dst=/lab,readonly" \
        --workdir /lab "$prometheus_image" "$@"
}

promtool --version
promtool check rules alerts.yml
promtool test rules alerts.test.yml
