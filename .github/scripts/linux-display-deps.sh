#!/usr/bin/env bash
# Installs Xvfb and GTK 3 when the runner image lacks them.
set -euo pipefail

missing=()
command -v xvfb-run >/dev/null 2>&1 || missing+=(xvfb xauth)
ldconfig -p | grep -q 'libgtk-3\.so\.0' || missing+=(libgtk-3-0t64)
if [ ${#missing[@]} -eq 0 ]; then
  echo "Xvfb and GTK 3 present"
  exit 0
fi
echo "Installing: ${missing[*]}"
sudo apt-get update
sudo apt-get install -y --no-install-recommends "${missing[@]}"
