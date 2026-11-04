# qcaret-bench

JMH benchmarks and a latency and heap harness for `RopeStorage` in
`core.buffer`. Not shipped and not scanned by SpotBugs. Input is generated in
memory, never read from the repository.

## Map

| Class | Contents |
|---|---|
| `Corpus` | Seeded input: `ASCII` source-like lines of 80 chars, `MIXED` with 30% of the chars outside Latin-1, `CRLF` with CR LF breaks, `MIXED_EOL` with LF, CR LF and CR drawn at random, `LONGLINE` with no line break |
| `Workloads` | Edit sequences W2 to W8, seed 42 |
| `BuildBenchmark` | W1 build time, and `decode`, the UTF-8 decode of the same text |
| `EditBenchmark` | W3 random edits, W5 replace-all, W8 undo and redo; one run per iteration |
| `ReadBenchmark` | W6 `lineAt`, `offsetOfLine`, `line` and a reused `LineView` on 10 MiB; W7 `TextCursor`, `charAt` and chunk reads of 100 MiB; both after W3 |
| `StorageHarness` | W2 and W4 latency percentiles; live heap after W1 and after W3 |

## Running

```sh
./mvnw -q package -pl qcaret-bench -am -DskipTests
java -jar qcaret-bench/target/benchmarks.jar BuildBenchmark
java -jar qcaret-bench/target/benchmarks.jar ReadBenchmark
java -jar qcaret-bench/target/benchmarks.jar EditBenchmark
java -XX:MaxRAMPercentage=50 -cp qcaret-bench/target/benchmarks.jar \
  io.github.ajdailey.qcaret.bench.StorageHarness
```

`java` is Java 21. Add `-p corpus=LONGLINE -p sizeMiB=4` to a `BuildBenchmark`
run for the 4 MiB single line. Every JMH benchmark runs 2 forks of 5 warm-up
and 10 measured iterations at `-XX:MaxRAMPercentage=50`. Run on an idle host
and record `uptime` before and after: timings taken under load do not
reproduce.

## Workload notes

- The harness reads the live heap after three `System.gc()` calls, which are
  full collections under G1. W2 stops a size after 120 s and reports the
  keystrokes completed.
- `ASCII` and `MIXED` generate the same text as when the storage was chosen,
  so their results compare with that run.
