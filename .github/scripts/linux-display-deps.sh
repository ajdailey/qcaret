#!/usr/bin/env bash
# Installs Xvfb, GTK 3 and the X11 utilities (xprop and xwininfo for the
# window stacking and icon tests) when the runner image lacks them.
set -euo pipefail

missing=()
command -v xvfb-run >/dev/null 2>&1 || missing+=(xvfb xauth)
ldconfig -p | grep -q 'libgtk-3\.so\.0' || missing+=(libgtk-3-0t64)
command -v xwininfo >/dev/null 2>&1 || missing+=(x11-utils)
if [ ${#missing[@]} -eq 0 ]; then
  echo "Xvfb, GTK 3 and the X11 utilities present"
  exit 0
fi
echo "Installing: ${missing[*]}"
.github/scripts/apt-update.sh
sudo apt-get install -y --no-install-recommends "${missing[@]}"
