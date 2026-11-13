# qcaret-bench

JMH benchmarks of `RopeStorage` in `core.buffer`, `DisplayProjection` and
HyperSearch, latency and heap harnesses for the storage, for search and for Go
to Symbol, and a startup harness for the shipped application. Not shipped and not scanned by
SpotBugs. Input is generated, never read from the repository.

## Map

| Class | Contents |
|---|---|
| `Corpus` | Seeded input: `ASCII` source-like lines of 80 chars, `MIXED` with 30% of the chars outside Latin-1, `CRLF` with CR LF breaks, `MIXED_EOL` with LF, CR LF and CR drawn at random, `LONGLINE` with no line break |
| `Workloads` | Edit sequences W2 to W8, seed 42 |
| `BuildBenchmark` | W1 build time, and `decode`, the UTF-8 decode of the same text |
| `EditBenchmark` | W3 random edits, W5 replace-all, W8 undo and redo; one run per iteration |
| `ReadBenchmark` | W6 `lineAt`, `offsetOfLine`, `line` and a reused `LineView` on 10 MiB; W7 `TextCursor`, `charAt` and chunk reads of 100 MiB; both after W3 |
| `StorageHarness` | W2 and W4 latency percentiles; live heap after W1 and after W3 |
| `StartupHarness` | Process start to the first paint of file text, for `qcaret.jar` or a bundle launcher |
| `ProjectionBenchmark` | `DisplayProjection` on a `Document`: `build`, `rewrap` at a width change, `foldAndUnfold`, `foldAllAndUnfoldAll`, `toDisplay`, `toModel`, `edit`, `editLineBreak` |
| `SearchTree` | Seeded tree in a temporary directory: 20,000 files of 2 KiB and 4 of 16 MiB; the four `Query` values, literal and regex, few and many hits |
| `SearchBenchmark` | Time to complete, `fileSearch` into a counting sink and `hyperSearch` into a `ResultSet` |
| `SearchHarness` | Time to first result, cancel latency, and a finished set's live heap against its charge |
| `SymbolHarness` | Go to Symbol on generated 1,000,000-line `JAVA` (a definition every 7 lines), `JAVA_SPARSE` (every 200) and `MARKDOWN` (a heading every 120): the first scan, the filter and sort a cached list leaves, `rescan` and a whole scan after one-line edits at lines 1,006 and 500,006, clear of the Markdown fences, with their results compared, and a `SymbolScan`'s live heap against its charge |

## Running

```sh
./mvnw -q package -pl qcaret-bench -am -DskipTests
java -jar qcaret-bench/target/benchmarks.jar BuildBenchmark
java -jar qcaret-bench/target/benchmarks.jar ReadBenchmark
java -jar qcaret-bench/target/benchmarks.jar EditBenchmark
java -cp qcaret-bench/target/benchmarks.jar \
  io.github.ajdailey.qcaret.bench.StorageHarness [w1heap w2 w3heap w4]
xvfb-run -a env GDK_BACKEND=x11 java -cp qcaret-bench/target/benchmarks.jar \
  io.github.ajdailey.qcaret.bench.StartupHarness TARGET FILE SETTINGS [RUNS]
java -jar qcaret-bench/target/benchmarks.jar ProjectionBenchmark
java -jar qcaret-bench/target/benchmarks.jar SearchBenchmark
java -cp qcaret-bench/target/benchmarks.jar \
  io.github.ajdailey.qcaret.bench.SearchHarness [first cancel heap]
java -cp qcaret-bench/target/benchmarks.jar \
  io.github.ajdailey.qcaret.bench.SymbolHarness [JAVA JAVA_SPARSE MARKDOWN]
```

`java` is Java 21. Add `-p corpus=LONGLINE -p sizeMiB=4` to a `BuildBenchmark`
run for the 4 MiB single line. Every JMH benchmark runs 2 forks of 5 warm-up
and 10 measured iterations at `-XX:MaxRAMPercentage=50`. Run on an idle host
and record `uptime` before and after: timings taken under load do not
reproduce.

### Heap

`StorageHarness`, `SearchHarness` and `SymbolHarness` run at the shipped launchers'
`-XX:MaxRAMPercentage=50`: a JVM started without a heap option relaunches the
harness with it, and the first output line prints the options in force. A heap option on the command line
(`-Xmx`, `-XX:MaxRAMPercentage`, `-XX:MaxRAM`, `-XX:MaxHeapSize`) runs in place;
`-Dqcaret.bench.heap=jvm` runs in place at the JVM default (25% of RAM). Give a
run with JFR or native memory tracking a heap option: without one, both JVMs
apply them.

### Startup

`StartupHarness` launches QCaret `RUNS` times (6 by default), one at a time, with
`--settings SETTINGS -m -d debug FILE`, and times each from process start to the
DEBUG line `First paint of file text`, which `FirstTextPaint` logs on the first
paint with text. The first run also fills the settings directory and, for `qcaret.jar`,
extracts the SWT natives, so it is reported apart from the median of the rest.

`TARGET` is `qcaret.jar`, run with the harness JVM's `java` and
`-XX:MaxRAMPercentage=50`, or an executable launcher: extract the bundle archive
from `dist/` first, because the staging tree under `qcaret-dist/target` has no
execute bits. On Linux `--settings` also moves `user.home`, so QCaret writes only
inside `SETTINGS`, which holds each run's output as `startup-<n>.out`. Linux only:
on macOS the bootstrap relaunch of a `.jar` target drops the heap option.

### Scripts

| Script | Does |
|---|---|
| `scripts/heap-run.sh PREFIX [WORKLOAD...]` | `StorageHarness` at `$HEAP` (default `-Xmx1g`) with JFR, a GC log and native memory tracking; writes `<prefix>.out`, `.jfr`, `.gc.log`, `.nmt` (`VM.native_memory summary` every 2 s) and `.heap` (`jdk.GCHeapSummary`) |
| `scripts/cold.sh TARGET FILE SETTINGS [RUNS]` | `RUNS` rounds (5 by default) of one `StartupHarness` launch, each after `posix_fadvise` evicts `FILE` and `TARGET`'s tree from the page cache. Needs no root; system libraries stay cached. Run it under `xvfb-run -a` with `GDK_BACKEND=x11` |

The scripts use `$JAVA_HOME/bin/java` when set; build `qcaret-bench` first.

### Tab close

`TabsProbe` in `qcaret-ui-swt` opens, for each count in `qcaret.bench.tabs`,
that many files of `qcaret.bench.lines` lines (2,000 by default), shows the
first tab and times Close All: `task` is the command's UI task, `withQueued`
adds the UI work it queued.

```sh
xvfb-run -a ./mvnw -pl qcaret-ui-swt -am package -Dtest=TabsProbe \
  -Dsurefire.failIfNoSpecifiedTests=false -Dqcaret.bench.tabs=25,100
```

## Workload notes

- The harness reads the live heap after three `System.gc()` calls, which are
  full collections under G1. W2 stops a size after 120 s and reports the
  keystrokes completed.
- `ProjectionBenchmark.Shape`: `LINES` has 1,000,000 lines of 80 chars, `LINES_WRAP`
  wraps them at 60 columns, `LINES_FOLDS` collapses 99,999 regions. `LONGLINE` is
  4 MiB, split but not wrapped. `LONGLINE_WRAP` is 1,000,000 chars: an edit on a
  longer wrapped line passes `SYNC_CHARS`, which drops the wrap until a rewrap, so
  repeated edits would measure the unwrapped path.
- `edit` and `editLineBreak` run through `Document.apply`, so they include the rope
  and the undo step, and report the mean of an insert and its removal.
- `SearchTree` needs about 105 MiB in `java.io.tmpdir` and deletes itself. A
  many-hits `hyperSearch` ends at the 100,000-match limit, a `fileSearch` reads the
  whole tree. The page cache is warm after the first run.
- `SearchBenchmark` and `SearchHarness` pump HyperSearch on a `ManualUiDispatcher`,
  which runs the 100 ms flush timer without its delay.
- `ASCII` and `MIXED` generate the same text as when the storage was chosen,
  so their results compare with that run.

## Testing

| Class | Covers |
|---|---|
| `StorageHarnessTest` | Which JVM options count as heap options, so the harness runs in place instead of relaunching |
| `SearchTreeTest` | The rare word in every hundredth file and each large file of `SearchTree`, and the tree deleted afterwards |
