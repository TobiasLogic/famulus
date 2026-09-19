#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
target="$project_dir/libs/baritone-api-fabric-1.19.0.jar"
expected='eca6e2fdf43c6657fe9fea10a8f9a7572d78dc91ad389b998b808bd28fa5b5d1'
mkdir -p "$project_dir/libs"
if [[ -f "$target" ]] && [[ "$(sha256sum "$target" | cut -d ' ' -f 1)" == "$expected" ]]; then
    echo 'Pinned Baritone API Fabric mod is already verified.'
    exit 0
fi
temporary="$(mktemp "$project_dir/libs/.baritone.XXXXXX")"
trap 'rm -f -- "$temporary"' EXIT
curl --fail --location --retry 3 --max-time 180 \
    'https://github.com/cabaletta/baritone/releases/download/v1.19.0/baritone-api-fabric-1.19.0.jar' \
    --output "$temporary"
actual="$(sha256sum "$temporary" | cut -d ' ' -f 1)"
if [[ "$actual" != "$expected" ]]; then
    echo 'Baritone checksum mismatch; refusing to install download.' >&2
    exit 1
fi
mv -- "$temporary" "$target"
echo "Verified: $target"
