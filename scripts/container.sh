#!/usr/bin/env bash
# Runs the containerized build on Linux and WSL; Windows uses container.bat.
# compose.yaml holds mounts, caches and environment; this script maps
# subcommands, sets the output directory and runs as the caller.
set -euo pipefail

cd "$(dirname "$0")/.."

SERVICE=build
IMAGE="${QCARET_IMAGE:-qcaret-build:local}"

say() { printf '==> %s\n' "$1"; }
die() { printf '%s\n' "$1" >&2; exit 1; }

usage() {
  cat <<'USAGE'
usage: container.sh [--rebuild] [--out DIR] [command]

  (no command)   clean package -Pall-targets: the seven archives into dist/
  build          same as no command
  check          xvfb-run ./mvnw clean verify at 1280x1024: every test, SWT included
  clean          ./mvnw clean, and empty dist/
  shell          an interactive shell in the build image
  <anything>     run verbatim inside the image at the repository root

  --rebuild      rebuild the build image first
  --out DIR      write the archives to DIR instead of dist/

environment:
  QCARET_IMAGE              build image name (default qcaret-build:local)
  QCARET_CONTAINER_ENGINE   docker or podman (default: whichever is on PATH)
  QCARET_OUT                output directory, as --out
USAGE
}

# Under MSYS a mount source must be a Windows path; container.bat handles that.
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    die "run scripts/container.bat on Windows; this script is for Linux and WSL" ;;
esac

ENGINE="${QCARET_CONTAINER_ENGINE:-}"
if [ -z "$ENGINE" ]; then
  for candidate in docker podman; do
    if command -v "$candidate" >/dev/null 2>&1; then ENGINE="$candidate"; break; fi
  done
fi
[ -n "$ENGINE" ] || die "no docker or podman on PATH"
"$ENGINE" compose version >/dev/null 2>&1 \
  || die "$ENGINE has no compose support; install the compose plugin"

REBUILD=0
OUT_DIR="${QCARET_OUT:-dist}"
while [ $# -gt 0 ]; do
  case "$1" in
    --rebuild) REBUILD=1; shift ;;
    --out) [ $# -ge 2 ] || die "--out needs a directory"; OUT_DIR="$2"; shift 2 ;;
    --out=*) OUT_DIR="${1#--out=}"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) break ;;
  esac
done

INTERACTIVE=0
SUB="${1:-build}"
CMD=()
case "$SUB" in
  # Empty: compose.yaml's command runs.
  build) ;;
  check) CMD=(xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -B clean verify) ;;
  # dist/ is a mount source: empty it, never remove it.
  clean) CMD=(sh -c './mvnw -B clean && find dist -mindepth 1 -delete') ;;
  shell) CMD=(bash); INTERACTIVE=1 ;;
  *) CMD=("$@") ;;
esac

# A mount source must exist; one the engine creates belongs to root.
mkdir -p dist "$OUT_DIR"
QCARET_OUT="$(cd "$OUT_DIR" && pwd)"
QCARET_UID="$(id -u)"
QCARET_GID="$(id -g)"
export QCARET_OUT QCARET_UID QCARET_GID

if [ "$REBUILD" -eq 1 ] || ! "$ENGINE" image inspect "$IMAGE" >/dev/null 2>&1; then
  say "Building $IMAGE"
  "$ENGINE" compose build "$SERVICE"
elif [ "$(stat -c %Y docker/Dockerfile)" -gt \
       "$(date -d "$("$ENGINE" image inspect -f '{{.Created}}' "$IMAGE")" +%s)" ]; then
  say "Note: $IMAGE is older than docker/Dockerfile; --rebuild refreshes it"
fi

run=("$ENGINE" compose run --rm)
# compose refuses a TTY when either end is not a terminal.
if [ "$INTERACTIVE" -eq 0 ] && { [ ! -t 0 ] || [ ! -t 1 ]; }; then
  run+=(-T)
fi

if [ "$SUB" = "build" ]; then
  say "Archives: $QCARET_OUT"
fi

exec "${run[@]}" "$SERVICE" ${CMD[@]+"${CMD[@]}"}
