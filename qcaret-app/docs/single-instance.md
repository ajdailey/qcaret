# Single instance

The lock, endpoint and handoff protocol in `app.ipc` of
[qcaret-app](../README.md). The launcher's side (claim, successor polling,
`BESIDE`) is in the `qcaret-launcher` README.

Without `-m`, a launch hands itself to the primary instance of its settings
directory over TCP on the loopback address. A failure that no new primary
follows opens the launch's own window and logs one WARN. A `-m` process opens
no socket.

| File | Contents | Owner |
|---|---|---|
| `instance.lock` | empty; a `ProcessLock` held for the primary's life | primary |
| `instance.endpoint` | `version=1`, `port=<n>`, `token=<64 hex>`; owner-only, through `AtomicFiles.writeOwnerOnly` | primary |

## Lock and endpoint

`InstanceServer.claim` takes the lock. The lock decides liveness, never the
endpoint file: the OS releases it when the process dies, and the holder deletes
any stale endpoint, binds an ephemeral loopback port and writes a new token.
`close` stops accepting, deletes the endpoint, then releases the lock; in any
other order a new primary can lose its own endpoint. Close it before
`Workers.close`, which waits for the accept thread.

`ProcessLock` is in [qcaret-core
files](../../qcaret-core/docs/files-and-encodings.md#process-lock). A second
claim in one JVM gets null. On a JDK without `flock`, `claim` throws and the
launch runs alone. Until `close`, `SafePaths.holdLock` makes every open and
save of `instance.lock` fail with `INSTANCE_LOCK_REFUSED`, shown as the usual
open or save failure: a save replaces the file and leaves the lock on the old
one, so the next launch becomes a second primary (measured on Linux). A `-m`
process holds no lock and does not refuse the file.

## Protocol

`InstanceClient.handOff` runs in the launcher before the Display exists. It
waits up to 2 s for a missing endpoint, then runs one exchange on non-blocking
channels against one 35 s deadline (`LineChannel`), because the ArchUnit rules
ban `java.net.Socket` and its timeouts. Lines are printable ASCII, at most 16
KiB, 256 `OPEN` lines and 1 MiB per connection.

```text
C: QCARET 1 HELLO nonceC
S: QCARET 1 PROOF nonceS HMAC(token, "server" nonceC nonceS)
C: AUTH HMAC(token, "client" nonceS nonceC)
C: WINDOW 0|1 x y width height                (1 = -n; - = not given)
C: OPEN path line column readOnly encoding    (repeated; 0 and - = none)
C: DIFF left right                            (at most once)
C: END
S: OK | ERR
```

- Nonces are 32 bytes from `SecureRandom`, compared with
  `MessageDigest.isEqual`.
- Paths and encoding names travel as base64url of their UTF-8 bytes, so a file
  name holding a line break cannot split a line.
- The server proves the token first, so a client sends no file to a process
  without it; a peer that fails `AUTH` gets no reply.
- A path must be absolute and an encoding known to the runtime, else the
  request gets `ERR`.
- `InstanceClient.refusal` turns the handoff off for a settings directory
  another user owns or can write (POSIX).
- A lost `OK` after the primary acted opens the files twice: the launch cannot
  tell.

## Queued launches

Simultaneous launches queue. `InstanceServer` serves `MAX_CONNECTIONS` (4)
connections at once; further launches wait in the socket backlog (`BACKLOG`,
128). A connection reads authentication and the request within 5 s. `start`
runs before the first window and `deliverTo` names the `Receiver` after it, so
until then each authenticated request stays on its connection, at most 4
requests of 256 files. `deliverTo` does not block; the first released thread
delivers the queue in arrival order under a lock the UI thread never takes.

| Event | Held request | Client |
|---|---|---|
| `deliverTo` | Delivered in arrival order | `OK` once the `Receiver` took it |
| `close` | Refused | `ERR` |
| `WINDOW_BOUND` (30 s from `claim`) with no `deliverTo` | Refused; a later `deliverTo` takes later handoffs | `ERR` |
| Primary process dies | Lost with the connection | Connection error |
| Primary accepts and never answers | None | Time-out at `EXCHANGE_BOUND` (35 s) |

`InstanceClient.EXCHANGE_BOUND` must exceed `WINDOW_BOUND`, so a client reads
the `ERR` instead of timing out. `OK` means the running instance holds the
launch: a request `WindowSet` keeps during a restore has its `OK` already.
