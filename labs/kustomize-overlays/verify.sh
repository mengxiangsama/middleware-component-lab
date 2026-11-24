#!/bin/sh
set -eu

lab_dir=$(cd "$(dirname "$0")" && pwd)
output_dir=$(mktemp -d)
trap 'rm -rf -- "$output_dir"' EXIT

kustomize version
kubeconform -v

kubernetes_version=1.35.0
schema_revision=0c4dd99d9873f7b4c2da24c008476f5841adbe2d
schema="$output_dir/deployment-apps-v1.json"
curl -fsSL --max-time 30 --retry 2 -o "$schema" \
    "https://raw.githubusercontent.com/yannh/kubernetes-json-schema/$schema_revision/v$kubernetes_version-standalone-strict/deployment-apps-v1.json"

validate() {
    kubeconform -strict -summary -kubernetes-version "$kubernetes_version" \
        -schema-location "$schema" "$1"
}

verify_overlay() {
    environment=$1
    replicas=$2
    tag=$3
    manifest="$output_dir/$environment.yaml"

    kustomize build "$lab_dir/overlays/$environment" > "$manifest"
    grep -Fxq "  namespace: orders-$environment" "$manifest"
    grep -Fxq "  replicas: $replicas" "$manifest"
    grep -Fxq "      - image: registry.example.com/orders:$tag" "$manifest"
    validate "$manifest"
    printf '%s: validated orders-%s, replicas=%s, image tag=%s\n' \
        "$environment" "$environment" "$replicas" "$tag"
}

verify_overlay staging 1 1.4.0-rc.1
verify_overlay production 3 1.4.0

invalid="$output_dir/invalid.yaml"
kustomize build "$lab_dir/overlays/invalid" > "$invalid"
grep -Fxq '  replicas: "3"' "$invalid"
if validate "$invalid" > "$output_dir/invalid.log" 2>&1; then
    echo "Expected kubeconform to reject a string replica count" >&2
    exit 1
fi
cat "$output_dir/invalid.log"
grep -Fq '/spec/replicas' "$output_dir/invalid.log"
grep -Fq 'got string, want null or integer' "$output_dir/invalid.log"
