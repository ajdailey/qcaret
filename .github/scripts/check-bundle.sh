#!/usr/bin/env bash
# Unpacks the $TARGET bundle from dist/, checks that its runtime reports
# os.arch $EXPECTED_ARCH and has jdk.charsets, and starts the bundle's
# launcher until the main window opens. Linux uses a private Xvfb display.
set -euo pipefail

work="$PWD/bundle-run"
rm -rf "$work"
mkdir -p "$work"
case "$TARGET" in
  linux-*)
    tar -xzf dist/qcaret-*-"$TARGET".tar.gz -C "$work"
    launcher="$work/qcaret/bin/qcaret"
    runtime="$work/qcaret/runtime"
    ;;
  macos-*)
    unzip -q dist/qcaret-*-"$TARGET".zip -d "$work"
    launcher="$work/QCaret.app/Contents/MacOS/QCaret"
    runtime="$work/QCaret.app/Contents/runtime/Contents/Home"
    ;;
  *)
    echo "unsupported target $TARGET" >&2
    exit 1
    ;;
esac
runtime="$(cd "$runtime" && pwd -P)"

if [ "$(uname -s)" = Darwin ]; then
  codesign --verify --deep --strict "$work/QCaret.app"
  echo "codesign: QCaret.app matches its seal"
fi
"$runtime/bin/java" --list-modules | grep '^jdk\.charsets@' >/dev/null \
  || { echo "FAIL: runtime lacks jdk.charsets" >&2; exit 1; }

arch="$("$runtime/bin/java" -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^ *os\.arch = //p')"
echo "runtime $runtime: os.arch=$arch, expected $EXPECTED_ARCH"
[ "$arch" = "$EXPECTED_ARCH" ]

if [ "$(uname -s)" = Linux ]; then
  Xvfb :99 -screen 0 1280x1024x24 >/dev/null 2>&1 &
  xvfb=$!
  trap 'kill "$xvfb" 2>/dev/null || true' EXIT
  export DISPLAY=:99 GDK_BACKEND=x11
fi

printf 'mock line\n' >"$work/notes.txt"
"$launcher" -m -d 1 --settings "$work/settings" "$work/notes.txt" >"$work/out.log" 2>&1 &
app=$!
for _ in $(seq 1 60); do
  grep -q 'Main window open' "$work/out.log" && break
  kill -0 "$app" 2>/dev/null || break
  sleep 1
done
kill "$app" 2>/dev/null || true
echo "--- launcher output"
cat "$work/out.log"
echo "---"

grep -qF "from $runtime" "$work/out.log" \
  || { echo "FAIL: QCaret did not report java.home $runtime" >&2; exit 1; }
grep -q 'Main window open' "$work/out.log" \
  || { echo "FAIL: no main window within 60 s" >&2; exit 1; }
echo "PASS: $TARGET launcher ran $runtime (os.arch $arch) and opened the main window"
