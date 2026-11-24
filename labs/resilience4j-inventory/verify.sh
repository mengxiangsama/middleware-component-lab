#!/bin/sh
set -eu

lab_dir=$(cd -- "$(dirname -- "$0")" && pwd)
demo_dir=$(mktemp -d)
trap 'rm -rf -- "$demo_dir"' EXIT

central=https://repo.maven.apache.org/maven2
for module in circuitbreaker retry core; do
    artifact="resilience4j-$module"
    jar="$artifact-2.3.0.jar"
    curl -fsSL --retry 3 --max-time 30 -o "$demo_dir/$jar" \
        "$central/io/github/resilience4j/$artifact/2.3.0/$jar"
done
for artifact in slf4j-api slf4j-nop; do
    jar="$artifact-1.7.30.jar"
    curl -fsSL --retry 3 --max-time 30 -o "$demo_dir/$jar" \
        "$central/org/slf4j/$artifact/1.7.30/$jar"
done

if [ -n "${JAVA_HOME:-}" ]; then
    java_cmd="$JAVA_HOME/bin/java"
else
    java_cmd=java
fi
"$java_cmd" --class-path "$demo_dir/*" "$lab_dir/InventoryProtectionDemo.java"
