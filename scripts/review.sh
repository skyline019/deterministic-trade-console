#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--with-integration" ]; then
    ./mvnw -B -Pintegration verify
else
    ./mvnw -B verify
fi
