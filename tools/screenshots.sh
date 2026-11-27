#!/bin/sh
# Writes docs/images/qcaret-light.png and qcaret-dark.png (the main window,
# 1100x700) and the user guide's docs/images/guide/*.png from generated mock
# files under Xvfb. Linux (GTK 3) only. Needs xvfb-run, WebKitGTK for the Browser
# preview image and a JDK 21 in JAVA_HOME;
# the test JVMs use target/test-home as user.home, so nothing is written to the
# real home directory. The files are written to /tmp/qcaret-demo, the path the
# screenshots show, created owner-only (the run stops if it exists) and removed
# afterwards. Every image the guide names must exist before this runs, because
# the build embeds them; the next build embeds the new ones.
# XDG_CONFIG_HOME names the tests' settings.ini, so GTK draws Adwaita, light or
# dark as each shot asks, whatever the desktop theme is.
#
# "screenshots.sh installer [<run-id>]" instead copies the Windows setup pages
# the guide shows from the installer-screenshots-windows-x86_64 artifact of CI
# run <run-id>, by default the latest successful CI run on main, into
# docs/images/guide/installer-{type,folder,finish}.png. Needs gh and network.
set -eu

root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

if [ "${1:-}" = installer ]; then
    run=${2:-$(gh run list --workflow ci.yml --branch main --status success \
        --limit 1 --json databaseId --jq '.[0].databaseId')}
    [ -n "$run" ] || { echo "no successful CI run on main" >&2; exit 1; }
    pages=$(mktemp -d)
    trap 'rm -rf "$pages"' EXIT
    gh run download "$run" -n installer-screenshots-windows-x86_64 -D "$pages"
    # The setup walk's pages 3, 4 and 6: installation type, folder, finish.
    for pair in 3:type 4:folder 6:finish; do
        set -- "$pages"/[0-9][0-9]-setup-"${pair%%:*}".png
        [ $# -eq 1 ] && [ -f "$1" ] || { echo "run $run has no setup page ${pair%%:*}" >&2; exit 1; }
        cp "$1" "docs/images/guide/installer-${pair#*:}.png"
    done
    echo "copied from run $run"
    ls -l docs/images/guide/installer-*.png
    exit 0
fi

files=/tmp/qcaret-demo
mkdir -m 700 "$files"
trap 'rm -rf "$files"' EXIT
XDG_CONFIG_HOME="$root/qcaret-ui-swt/src/test/resources/gtk-config/adwaita"
export XDG_CONFIG_HOME
xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -q package -pl qcaret-launcher -am \
    -Dtest='ScreenshotsTest,GuideScreenshotsTest,MarkdownGuideShotsTest,EditorSchemeShotsTest,HexShotsTest' -Dsurefire.failIfNoSpecifiedTests=false \
    -Dqcaret.screenshots="$root/docs/images" -Dqcaret.guide.shots="$root/docs/images/guide" \
    -Dqcaret.screenshots.files="$files"
ls -l "$root/docs/images" "$root/docs/images/guide"
