#!/bin/sh
# Writes docs/images/qcaret-light.png and qcaret-dark.png (the main window,
# 1100x700) and the user guide's docs/images/guide/*.png from generated mock
# files under Xvfb. Linux (GTK) only. Needs xvfb-run and a JDK 21 in JAVA_HOME;
# the test JVMs use target/test-home as user.home, so nothing is written to the
# real home directory. The files are written to /tmp/qcaret-demo and removed
# afterwards. Every image the guide names must exist before this runs, because
# the build embeds them; the next build embeds the new ones.
# XDG_CONFIG_HOME names the tests' settings.ini, so GTK draws Adwaita, light or
# dark as each shot asks, whatever the desktop theme is.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
files=/tmp/qcaret-demo
cd "$root"
trap 'rm -rf "$files"' EXIT
XDG_CONFIG_HOME="$root/qcaret-ui-swt/src/test/resources/gtk-config/adwaita"
export XDG_CONFIG_HOME
xvfb-run -a -s "-screen 0 1280x800x24" ./mvnw -q package -pl qcaret-launcher -am \
    -Dtest='ScreenshotsTest,GuideScreenshotsTest,EditorSchemeShotsTest' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqcaret.screenshots="$root/docs/images" -Dqcaret.guide.shots="$root/docs/images/guide" \
    -Dqcaret.screenshots.files="$files"
ls -l "$root/docs/images" "$root/docs/images/guide"
