#!/usr/bin/env bash
# Default command of the build container: the archives, installers, packages
# and SBOM into dist/, as CI's package job writes them. Runs inside the image,
# from the repository root.
set -euo pipefail

cd "$(dirname "$0")/.."

./mvnw -B clean package -Pall-targets,sbom "$@" \
  org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom
.github/scripts/sbom-components.sh

echo ">>> Archives in dist/:"
ls -l dist
