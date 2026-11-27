#!/usr/bin/env bash
# Refreshes the apt lists. A failing third-party source preinstalled on the runner
# (for example packages.microsoft.com answering 403) does not stop the job; a
# package that then cannot be found fails the install that needs it.
set -uo pipefail
sudo apt-get update || echo "apt-get update reported errors; continuing with the lists it fetched"
