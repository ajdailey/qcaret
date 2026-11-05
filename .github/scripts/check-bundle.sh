#!/usr/bin/env bash
# Unpacks the $TARGET bundle from dist/, verifies a macOS bundle's signature,
# checks that its runtime reports os.arch $EXPECTED_ARCH and has jdk.charsets
# and java.xml, and starts the bundle's launcher until the main window opens
# and the grammars are read, with no unhandled failure. Then starts the
# universal zip's bin/qcaret, which has no runtime, with $JAVA_HOME. Linux uses
# a private Xvfb display.
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
"$runtime/bin/java" --list-modules | grep '^java\.xml@' >/dev/null \
  || { echo "FAIL: runtime lacks java.xml" >&2; exit 1; }

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

# Runs launcher $1 until the main window opens; passes when QCaret reports
# java.home $2. $3 names the run's settings directory and output.
check_launcher() {
  local out="$work/$3.out"
  "$1" -m -d 1 --settings "$work/settings-$3" "$work/notes.txt" >"$out" 2>&1 &
  local app=$!
  for _ in $(seq 1 60); do
    grep -q 'Grammars read' "$out" && break
    kill -0 "$app" 2>/dev/null || break
    sleep 1
  done
  kill "$app" 2>/dev/null || true
  echo "--- $3 output"
  cat "$out"
  echo "---"
  grep -qF "from $2" "$out" \
    || { echo "FAIL: $3 did not report java.home $2" >&2; exit 1; }
  grep -q 'Main window open' "$out" \
    || { echo "FAIL: $3 opened no main window within 60 s" >&2; exit 1; }
  grep -q 'Grammars read: 0 skipped' "$out" \
    || { echo "FAIL: $3 did not read every grammar within 60 s" >&2; exit 1; }
  if grep -q 'Unhandled failure' "$out"; then
    echo "FAIL: $3 logged an unhandled failure" >&2
    exit 1
  fi
}

check_launcher "$launcher" "$runtime" bundle
echo "PASS: $TARGET launcher ran $runtime (os.arch $arch) and opened the main window"

universal="$(ls dist/qcaret-*.zip | grep -Ev -- '-(linux|macos|windows)-')"
unzip -q "$universal" -d "$work/universal"
jdk="$(cd "$JAVA_HOME" && pwd -P)"
check_launcher "$work/universal/qcaret/bin/qcaret" "$jdk" universal
echo "PASS: $universal bin/qcaret ran $jdk and opened the main window"
