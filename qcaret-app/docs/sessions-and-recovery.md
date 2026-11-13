# Sessions and recovery

The session file, per-file state and recovery files, in `app.session`,
`app.recovery` and `app.controller` of [qcaret-app](../README.md).

## Session file

`SessionStore` reads and writes `qcaret_session.conf`: a list of windows,
Recent Files and the per-file LRU. `Gui` reads the file before the Display.

| Case | Result |
|---|---|
| Over 4 MiB, does not parse, or another version | Loads as nothing with one WARN; kept as `.invalid` before the next save |
| No `version` key | Read as version 1 |
| A bad value | Falls back |
| A tab without a path or buffer | Skipped |

### Save limits

`SessionStore` saves at most 32 windows, 256 tabs over all windows, 20 Recent
Files and 500 per-file entries, and per window 32 retained searches, 1,000
expanded directories and 20 closed files (`MAX_*`); a save drops the entries
past a limit from the end of each list. `SessionController` hands each written
session to `SessionLimitNotice`: the first save of a run that drops any of
these, the per-file entries aside, logs one WARN with every `SessionOverflow`
count and shows one notice naming the kinds in the window activated last.
Later saves report nothing; when the first is the quit's save, only the WARN
is written. A window past the limit counts as one window; its tabs and lists
are not counted. The per-file LRU evicts its oldest entry with no notice.

### Bookmark comment limit

A session keeps 1,000 bookmark comments over all tabs, while one document may
hold 1,000; the first tabs in saved order keep theirs. `SessionStore` writes
the count a save leaves out as `droppedComments`, because a quit save has no
window for a notice. The next restore shows one notice in the window activated
last, and the save `finish` arms clears the key: `Session.droppedComments` is
part of the record's equality, so the capture differs from the file even with
no other change. An instance that does not restore leaves the key. Clear
History from such an instance rewrites the file and keeps the count. A missing
or malformed key reads as 0. The per-file LRU holds no comments.

## Restore and save

`SessionController` owns restore and save, one per `WindowSet`. Only an
instance started with `restore` true saves, so `-m` never replaces the session.

- The first saved window restores into the window open at start, and each
  further one into a new window at its saved bounds, in saved order; `Gui`
  places the first window from the session too.
- A window that cannot be created gives its tabs to the first. A window left
  without a tab opens an untitled document.
- Files on the command line (`--session` with files) open after the restore.
- After a `--window-*` start each saved window keeps the bounds it loaded.

| What | Restored |
|---|---|
| Tabs | One at a time in their saved order, then the saved active tab; a tab of a file that does not open, or is too large, is skipped and every name goes in one report |
| Per view | Selections, top line (kept through the rewraps of a wrapped window), collapsed folds; a fold applies once a region starts on its line, until an edit or a fold command |
| Per tab | Split arrangement and weights, focused view, tab width, spaces, forced charset, chosen grammar, binary view, bookmarks and comments, read-only state |
| Per window | Workspace folder, browser visibility, the browser's shown folder, filter and Hidden state, sash, expanded directories, results dock and its sash, the sash between the results and Bookmarks docks, Reopen Closed Tab's files, word wrap, whitespace and line-ending marks, overview strip, line numbers, Bookmarks list visibility, Always on Top (`AlwaysOnTop`; kept as saved, not applied, where the platform is unsupported) |
| Retained searches | Requests only, as `ResultSet.State.RESTORED` sets that Rerun fills; running every search at start could read whole trees |

A remembered charset the runtime lacks warns and detects; one that no longer
decodes the file warns and detects too.

### Save timing

A change arms one 2 s timer, and changes before it fires share it, so a burst
of edits cannot put a save off. The timer numbers a capture on the UI thread,
and a lifecycle worker writes it under `saveLock`, skipping any capture a later
one has written. An unchanged session writes nothing. New windows, closed
windows and tab moves arm the same timer.

A quit that asks arms no save from `quitStarted` on. `quitAnswered` keeps the
windows after the last answer and before the first close, so the last save
lists every tab and the file an untitled document was saved to at a prompt; the
Recent Files and the LRU are read at the end and hold what the closes added. A
process end during a prompt captures the live windows.

### Session lock

Only the holder of `SessionLock` (`qcaret_session.lock`, a `ProcessLock`)
writes `qcaret_session.conf`. `Gui` takes it before a start that would restore
and holds it until `run` returns, after the quit's last save. A start that
finds it held for 1 s runs as `BESIDE` does: Recent Files only, no restore, no
save, no window placement, one INFO line. This covers every launch that runs
alone (`Handoff.ALONE`) while another instance owns the session. A lock file
that cannot be created or locked, as in a read-only settings directory, leaves
the start restoring and owning the session, with one WARN. Until `close`,
`SafePaths.holdLock` makes every open and save of the lock file fail with
`SESSION_LOCK_REFUSED`.

### Clear History

Clear History leaves the result sets in the dock for the run and keeps them out
of every capture (`SearchController.forgetHistory`). `forgetSearches` removes
them from the file at once: an owner writes a new capture, and any other
instance rewrites it without searches through `SessionRewrite`, which holds
the session lock for the rewrite. While another instance holds the lock, the
non-owner writes nothing and the owner's last save stands, retained searches
included.

| `SessionRewrite.Result` | Notice |
|---|---|
| `REMOVED` | None |
| `FAILED` | `PREFERENCES_HISTORY_NOT_REMOVED` |
| `OWNED_ELSEWHERE` | `PREFERENCES_HISTORY_SESSION_OWNED`: close the owning instance and clear again |

Clear History during a restore also reaches the saved windows not restored yet:
`SessionController` restores them without their searches.

A launch that failed its handoff to a live primary gets Recent Files alone from
`Session.recentOnly`, so it restores nothing and no file state applies.

## Per-file state

`SessionController` keeps the per-file LRU (`FileLru`, `SessionStore.MAX_FILES`,
500 entries) behind `DocumentController.FileMemory`, keyed by the opened
document's real path (caret, top line, tab settings, forced charset, chosen
grammar). A closing file's state goes in; an opening file's comes out, in
`DocumentOpener.Listener.options` for the charset and after the load for the
rest. Reopen Closed Tab therefore lands where the file was. A width or
indentation the file declares replaces the remembered and session tab settings
([Per-file settings](documents.md#per-file-settings)).

## Recovery files

`RecoveryFile` is format version 1: a header, the body and a trailer digest,
streamed in chunks. A text with an unpaired surrogate goes as UTF-16BE, one
char at a time, because the JDK's coders replace such a char.

`RecoveryStore` holds `<settings>/recovery/<instance>/`. `owner.lock`, held
through a `ProcessLock`, decides liveness; `claimDead` gets null for this
process's own directory. `claimDead` locks each dead directory until its claim
closes, so two starts cannot list one buffer. On a JDK without `flock`, `open`
fails and recovery files are off. While the lock is held, `SafePaths.holdLock`
makes every open and save of that `owner.lock` fail with
`RECOVERY_LOCK_REFUSED`: a save replaces the file, the lock stays on the old
one, and a second start lists the live instance's buffers as a crash's
(measured on Linux).

`RecoveryController` runs one queue on `qcaret-io-` workers under `runLock`, so
a write cannot land after the delete that follows it. One controller and one
instance directory serve every window: `WindowSet.dirtyBuffers` lists them, and
a buffer id is unique in the instance. The dialog shows in the window activated
last.

| Event | Effect on the buffer's file |
|---|---|
| `recovery.intervalSeconds` | Dirty buffers whose revision is not on disk are written from a snapshot; a failed write is retried at the next interval, with one notice |
| Save, undo back to the saved text, close with discard | Deleted |
| Ordered quit | The instance directory goes |
| `quit` with dirty buffers (shutdown hook) | Written, directory kept, offered at the next start |
| Restore in the dialog | New file written first, then the old one deleted; a failed write keeps the old one until a retry succeeds or the buffer needs no recovery |
| Rename from the workspace browser | Written again at the next interval with the new path, whatever its revision |
| Damaged file | Listed, not restorable, kept; Discard is the user's choice |
| Recovery file cannot be read | Listed as "Recovery file cannot be read" with a WARN, kept; Restore reads it again; the directory's other files are listed as usual |
| Original cannot be read | Listed as `UNREADABLE`, restorable; the other files of the instance are unaffected |
| `recovery.keepDays` | Older files in dead directories go at start |

`addRecovered` opens the buffer dirty and writes nothing. When the original
still holds the buffer's base, the document takes the file's current baseline;
otherwise, or when the file cannot be read, it keeps the recorded base, so
`ExternalChanges` shows the change prompt and a save asks first. A clean tab of
the file gives way, in the window holding it; a dirty one keeps its text and
the row stays. Any other buffer opens in the window activated last.

## Quit snapshot

With `session.snapshot` on and this instance owning the session, a quit
(`SnapshotQuit`) writes every window's dirty buffers to `<settings>/snapshot/`
and asks about no document; a failed write falls back to the prompts. The
write runs again when the dirty buffers then differ from those written,
matched by buffer id and revision, not by position. The quit then waits for
every running Save a Copy As as the prompted quit does, with the same prompt
after `RunningCopies.BOUND`, and a failed copy stops it. The tabs name their
buffers, and the next start opens them dirty. The write deletes every snapshot
it does not name, and a restored buffer takes a new id, so a Quit during a
restore waits for `WindowSet.restoreEnded`, called at the end of
`SessionController.finish`. Canceling the running load ends that tab's restore.
Without an owner the quit prompts. A shutdown hook quit goes through recovery
files and the dialog instead.
