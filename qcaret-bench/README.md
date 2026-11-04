# qcaret-bench

Benchmarks: JMH probes and a headless harness for storage, editing and search.
Not shipped and not scanned by SpotBugs. It holds only the `bench` package so
far; the storage benchmark adds its workloads. Inputs go to a scratch
directory, never the repository.

```sh
./mvnw -pl qcaret-bench -am package -DskipTests
```

`target/benchmarks.jar` is a shaded jar with JMH and every benchmark
dependency; its main class is `org.openjdk.jmh.Main`.
