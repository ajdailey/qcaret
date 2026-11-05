#!/bin/sh
# Writes docs/images/qcaret-light.png and qcaret-dark.png (the main window,
# 1100x700) from generated mock files under Xvfb. Linux (GTK) only. Needs
# xvfb-run and a JDK 21 in JAVA_HOME; the test JVM uses target/test-home as
# user.home, so nothing is written to the real home directory. The files are
# written to /tmp/qcaret-demo and removed afterwards.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
files=/tmp/qcaret-demo
cd "$root"
trap 'rm -rf "$files"' EXIT
xvfb-run -a -s "-screen 0 1280x800x24" ./mvnw -q package -pl qcaret-launcher -am \
    -Dtest=ScreenshotsTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqcaret.screenshots="$root/docs/images" -Dqcaret.screenshots.files="$files"
ls -l "$root/docs/images"
