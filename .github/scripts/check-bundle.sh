#!/usr/bin/env bash
# Unpacks the $TARGET bundle from dist/, verifies a macOS bundle's signature,
# checks that its runtime reports os.arch $EXPECTED_ARCH and has jdk.charsets
# and java.xml, and starts the bundle's launcher until the main window opens
# and the grammars are read, with no unhandled failure. A second launch then
# hands a file to the running bundle and exits 0. Neither launch prints the
# JDK's native-access warning. The same run follows with the
# universal zip's bin/qcaret, which has no runtime, on $JAVA_HOME. Linux uses a
# private Xvfb display.
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
printf 'mock line\n' >"$work/handed.txt"

# Waits up to 60 s for line $2 in file $1 while process $3 runs.
await_line() {
  for _ in $(seq 1 60); do
    grep -q "$2" "$1" && return 0
    kill -0 "$3" 2>/dev/null || return 0
    sleep 1
  done
}

# Runs launcher $1 until the main window opens and the grammars are read, then
# hands it a file from a second launch; passes when QCaret reports java.home
# $2. $3 names the run's settings directory and output.
check_launcher() {
  local out="$work/$3.out" second_out="$work/$3-second.out"
  local settings="$work/settings-$3"
  "$1" -d 1 --settings "$settings" "$work/notes.txt" >"$out" 2>&1 &
  local app=$!
  await_line "$out" 'Main window open' "$app"
  await_line "$out" 'Grammars read' "$app"

  # A failed handoff opens its own window and never exits, so the wait is bounded.
  "$1" --settings "$settings" "$work/handed.txt" >"$second_out" 2>&1 &
  local second=$!
  for _ in $(seq 1 60); do
    kill -0 "$second" 2>/dev/null || break
    sleep 1
  done
  local second_status=running
  if ! kill -0 "$second" 2>/dev/null; then
    second_status=0
    wait "$second" || second_status=$?
  fi
  await_line "$out" 'Handoff of 1 files' "$app"

  kill "$second" 2>/dev/null || true
  kill "$app" 2>/dev/null || true
  echo "--- $3 output"
  cat "$out"
  echo "--- $3 second launch: status $second_status"
  cat "$second_out"
  echo "---"
  grep -qF "from $2" "$out" \
    || { echo "FAIL: $3 did not report java.home $2" >&2; exit 1; }
  grep -q 'Main window open' "$out" \
    || { echo "FAIL: $3 opened no main window within 60 s" >&2; exit 1; }
  grep -q 'Grammars read: 0 skipped' "$out" \
    || { echo "FAIL: $3 did not read every grammar within 60 s" >&2; exit 1; }
  [ "$second_status" = 0 ] \
    || { echo "FAIL: $3 second launch ended with status $second_status" >&2; exit 1; }
  grep -q 'Handoff of 1 files taken' "$out" \
    || { echo "FAIL: $3 primary took no handoff" >&2; exit 1; }
  if grep -q 'WARNING: A restricted' "$out" "$second_out"; then
    echo "FAIL: $3 printed the native-access warning" >&2
    exit 1
  fi
  if grep -q 'Unhandled failure' "$out"; then
    echo "FAIL: $3 logged an unhandled failure" >&2
    exit 1
  fi
}

check_launcher "$launcher" "$runtime" bundle
echo "PASS: $TARGET launcher ran $runtime (os.arch $arch), opened the main window and took a handoff"

universal="$(ls dist/qcaret-*.zip | grep -Ev -- '-(linux|macos|windows)-')"
unzip -q "$universal" -d "$work/universal"
jdk="$(cd "$JAVA_HOME" && pwd -P)"
check_launcher "$work/universal/qcaret/bin/qcaret" "$jdk" universal
echo "PASS: $universal bin/qcaret ran $jdk, opened the main window and took a handoff"
