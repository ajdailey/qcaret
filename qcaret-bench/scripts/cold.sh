#!/bin/sh
# Cold-cache start: RUNS rounds of one StartupHarness launch of TARGET on FILE,
# each after evicting FILE and TARGET's tree from the page cache. Eviction uses
# posix_fadvise, which needs no root and leaves system libraries cached. Linux
# only.
#
#   cold.sh TARGET FILE SETTINGS [RUNS]
#   cold.sh "$S/bundle/qcaret/bin/qcaret" "$S/ascii-100m.txt" "$S/cold" 5
#
# TARGET is a bundle's bin/qcaret or qcaret.jar. Build qcaret-bench first.
set -eu

[ $# -ge 3 ] || { echo "usage: cold.sh TARGET FILE SETTINGS [RUNS]" >&2; exit 2; }
target=$1
file=$2
settings=$3
runs=${4:-5}
scripts=$(cd "$(dirname "$0")" && pwd -P)
jar=$scripts/../target/benchmarks.jar
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
case $target in
    *.jar) tree=$target ;;
    *) tree=$(cd "$(dirname "$target")/.." && pwd -P) ;;
esac

evict() {
    sync
    find "$tree" "$file" -type f -exec python3 -I -c '
import os, sys
for path in sys.argv[1:]:
    fd = os.open(path, os.O_RDONLY)
    os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
    os.close(fd)
' {} +
}

round=1
while [ "$round" -le "$runs" ]; do
    evict
    echo "cold.round=$round"
    "$java" -cp "$jar" io.github.ajdailey.qcaret.bench.StartupHarness \
        "$target" "$file" "$settings" 1
    round=$((round + 1))
done
