#!/usr/bin/env bash
# Builds `clean package -Pall-targets -DskipTests` twice and compares the
# SHA-256 of every archive, qcaret.jar, qcaret.exe and every module jar. The
# second build runs under another time zone and umask. Prints one line per
# file and exits 1 if any differs. Run from the repository root on an x86-64
# Linux host; extra arguments go to Maven.
set -euo pipefail
export LC_ALL=C

work="$PWD/dist/reproducible"
rm -rf "$work"
mkdir -p "$work"

build() {
  local n="$1"
  shift
  ./mvnw -B -q clean package -Pall-targets -DskipTests \
    -Dqcaret.dist.dir="$work/dist$n" "$@"
  {
    (cd "$work/dist$n" && sha256sum -- *) | sed 's|  |  dist/|'
    sha256sum -- */target/*.jar qcaret-dist/target/qcaret.exe
  } | sort -k 2 > "$work/sha256-$n.txt"
}

build 1 "$@"
(umask 002 && TZ=Pacific/Chatham build 2 "$@")

join -j 2 -a 1 -a 2 -e missing -o 0,1.1,2.1 \
  "$work/sha256-1.txt" "$work/sha256-2.txt" > "$work/joined.txt"
status=0
while read -r file first second; do
  if [ "$first" = "$second" ]; then
    echo "same      $file"
  else
    echo "DIFFERENT $file"
    status=1
  fi
done < "$work/joined.txt"
exit "$status"
