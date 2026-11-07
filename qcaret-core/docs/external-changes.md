# External change detection

`FileCheck` and `FileMonitor` in `core.watch` of [qcaret-core](../README.md).
What the editor does with a result is in [qcaret-app external
changes](../../qcaret-app/docs/external-changes.md).

## FileCheck

`FileCheck.check(path, baseline, token)` compares the file at the path the user
opened with a `FileBaseline` (stamp, SHA-256, time taken) on a worker. The path
is read through symbolic links at each check, so a retargeted link compares its
new target.

| File at the path | Result |
|---|---|
| No regular file | `MISSING`; `UNCHANGED` when the baseline is null (no file existed) |
| Stamp equals the baseline's, baseline not racy | `UNCHANGED`, nothing read |
| Same SHA-256 | `UNCHANGED` with the new stamp: a touch, or the same bytes renamed over the file |
| Other SHA-256, or a file where the baseline is null | `CHANGED` with the new baseline |

A baseline is racy (`FileBaseline.racy`) while its modification time lies
within 2 s of the time it was taken. A file system with a coarse clock, a copy
that keeps times, or NTFS tunneling can then record a second write of the same
size with the same stamp; the digest decides instead. The next check after the
2 s returns the baseline again with a new time, which settles it. Without a
file key (Windows), a file replaced with the same size and modification time
under a settled baseline reads `UNCHANGED`.

`read` digests in 1 MiB blocks and repeats while the stamp changes during the
read, at most three times.

## FileMonitor

`FileMonitor` runs one `qcaret-watch-` thread while any file is watched. It
calls its listener with every watched file every `pollMillis`, on `checkAll`
(window activation), and with the files `check` names or a directory event
names. `pause` holds that thread for a listener waiting on a changing file.

- Each directory holding a watched file is registered once with a
  `WatchService` and released with its last file; a directory that cannot be
  registered is polled only.
- The poll stays on: a `WatchService` misses network mounts, polls on macOS,
  and never sees a link's target change in another directory.
- An exception from the listener goes to the thread's uncaught-exception
  handler once per run of failures, and checking goes on.
- The thread ends within 100 ms of the last `unwatch` or of `close`.
- `setPollMillis` takes effect at once: the next check is one new interval
  away.
