#!/bin/sh
# StorageHarness at a fixed heap with JFR, a GC log and native memory tracking.
# Writes PREFIX.out (harness output), PREFIX.jfr, PREFIX.gc.log, PREFIX.nmt
# (VM.native_memory summary every 2 s) and PREFIX.heap (heap after each GC).
#
#   heap-run.sh PREFIX [WORKLOAD...]
#   HEAP=-Xmx2g heap-run.sh "$S/runs/w1" w1heap
#
# HEAP defaults to -Xmx1g. Build qcaret-bench first.
set -eu

[ $# -ge 1 ] || { echo "usage: heap-run.sh PREFIX [WORKLOAD...]" >&2; exit 2; }
prefix=$1
shift
scripts=$(cd "$(dirname "$0")" && pwd -P)
jar=$scripts/../target/benchmarks.jar
bin=${JAVA_HOME:+$JAVA_HOME/bin/}
mkdir -p "$(dirname "$prefix")"

# A heap option on the command line keeps the harness in this JVM, so $! is
# the process to sample.
"${bin}java" ${HEAP:--Xmx1g} -XX:NativeMemoryTracking=summary \
    -XX:StartFlightRecording=filename="$prefix.jfr",settings=profile \
    -Xlog:gc:file="$prefix.gc.log" \
    -cp "$jar" io.github.ajdailey.qcaret.bench.StorageHarness "$@" >"$prefix.out" &
pid=$!
: >"$prefix.nmt"
while kill -0 $pid 2>/dev/null; do
    "${bin}jcmd" $pid VM.native_memory summary >>"$prefix.nmt" 2>/dev/null || true
    sleep 2
done
wait $pid
"${bin}jfr" print --events jdk.GCHeapSummary "$prefix.jfr" >"$prefix.heap"
cat "$prefix.out"
