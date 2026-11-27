#!/usr/bin/env bash
# Installs the $TARGET .deb from dist/ with apt, checks its files and desktop
# entry, runs check-bundle.sh against /opt/qcaret through /usr/bin/qcaret, and
# removes it. Then installs the .rpm with dnf in a Fedora container, runs
# `qcaret -v` and `rpm -V`, and removes it. Run from the repository root on an
# Ubuntu runner of the target's architecture, after linux-display-deps.sh.
set -euo pipefail

# Fedora 44, multi-architecture index.
fedora=fedora:44@sha256:15da97ed9aa94dd3d92c3925eb89520f6c80e07ea7ee24cba848d1f8b6912b3e

deb=$(ls dist/qcaret-*-"$TARGET".deb)
rpm=$(ls dist/qcaret-*-"$TARGET".rpm)
version=${deb#dist/qcaret-}
version=${version%-"$TARGET".deb}

echo "--- $deb"
dpkg-deb --info "$deb"
.github/scripts/apt-update.sh
sudo apt-get install -y --no-install-recommends x11-utils desktop-file-utils "./$deb"

dpkg-query -W -f '${Version}\n' qcaret | grep -qxF "${version//-/\~}-1" \
  || { echo "FAIL: package version is not ${version//-/\~}-1" >&2; exit 1; }
[ "$(readlink /usr/bin/qcaret)" = /opt/qcaret/bin/qcaret ] \
  || { echo "FAIL: /usr/bin/qcaret does not link to /opt/qcaret/bin/qcaret" >&2; exit 1; }
[ ! -e /opt/qcaret/qcaret.conf ] && [ ! -e /opt/qcaret/bin/qcaret.conf ] \
  || { echo "FAIL: the package holds qcaret.conf" >&2; exit 1; }
desktop-file-validate /usr/share/applications/qcaret.desktop
grep -qx 'StartupWMClass=QCaret' /usr/share/applications/qcaret.desktop \
  || { echo "FAIL: the desktop entry lacks StartupWMClass=QCaret" >&2; exit 1; }
for size in 16 22 24 32 48 64 128 256 512; do
  [ -f "/usr/share/icons/hicolor/${size}x${size}/apps/qcaret.png" ] \
    || { echo "FAIL: no ${size}x${size} icon" >&2; exit 1; }
done
[ -f /usr/share/icons/hicolor/scalable/apps/qcaret.svg ] \
  || { echo "FAIL: no scalable icon" >&2; exit 1; }
echo "PASS: $deb installed /opt/qcaret, /usr/bin/qcaret, the desktop entry and every icon"

QCARET_HOME=/opt/qcaret QCARET_LAUNCHER=/usr/bin/qcaret .github/scripts/check-bundle.sh

sudo apt-get remove -y qcaret
for path in /opt/qcaret /usr/bin/qcaret /usr/share/applications/qcaret.desktop; do
  [ ! -e "$path" ] || { echo "FAIL: $path remains after removal" >&2; exit 1; }
done
echo "PASS: removing the .deb removed /opt/qcaret, /usr/bin/qcaret and the desktop entry"

echo "--- $rpm"
docker run --rm -v "$PWD/dist:/dist:ro" -e RPM="/$rpm" "$fedora" sh -euc '
  rpm -qip "$RPM"
  rpm -qp --requires "$RPM"
  dnf install -y -q "$RPM"
  rpm -V qcaret
  /usr/bin/qcaret -v
  [ ! -e /opt/qcaret/qcaret.conf ]
  dnf remove -y -q qcaret
  [ ! -e /opt/qcaret ] && [ ! -e /usr/bin/qcaret ]
'
echo "PASS: $rpm installed with dnf on Fedora 44, ran qcaret -v and removed cleanly"
