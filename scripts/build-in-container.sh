#!/usr/bin/env bash
# Default command of the build container: the universal zip and all six
# bundles into dist/. Runs inside the image, from the repository root.
set -euo pipefail

cd "$(dirname "$0")/.."

./mvnw -B clean package -Pall-targets "$@"

echo ">>> Archives in dist/:"
ls -l dist
