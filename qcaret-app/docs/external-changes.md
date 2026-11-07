# External changes and Compare

`ExternalChanges`, reload and the change prompts, and `CompareController`, in
`app.controller` of [qcaret-app](../README.md). The file check itself is in
[qcaret-core external changes](../../qcaret-core/docs/external-changes.md).

## Checks

`ExternalChanges` checks each open document's file through `FileCheck` on the
`FileMonitor`'s `qcaret-watch-` thread: every `file.pollSeconds`, on window
activation (`ApplicationController.windowActivated`), on a directory event and
after Restore Run. It checks the path the user opened, kept per document beside
the real path that names the tab and deduplicates opens.

A result reaches `DocumentController` on the UI thread only when the document's
watch generation is unchanged: a save or a reload pauses the watch and raises
the generation, so the document's own write is never reported.

A directory event wakes a check during another program's write, so a `CHANGED`
result waits until the file's stamp holds for 100 ms, up to five waits, and is
then reported once with the last bytes read. The wait runs on the watch thread,
once per pass for all changed files. A file that never holds still is reported
after the fifth wait.

| Check result | Clean document | Dirty document |
|---|---|---|
| Same bytes, newer stamp | Stamp taken as the save baseline | Same, unless the user kept a change |
| Other bytes | Reload; with `file.autoReloadClean` off, the prompt | Prompt: Keep Mine, Reload, Compare |
| No file | Kept, marked missing in the tab and dirty | Same |
| File back with the same bytes | Missing mark cleared; clean again when the text is unchanged since | Missing mark cleared |

Save of a missing file writes it without a prompt and clears the mark.

## Reload

Reload compares the file with the text on a `qcaret-io-` worker, in two passes
that hold only the changed span (`DocumentFiles.reload`), and replaces the span
between the common prefix and suffix as one undo step. Each view's caret keeps
its line and column; the views keep their top line. The document is clean
afterwards, and undo returns to the text before, dirty.

- The charset is the one the user chose (Reopen with Encoding, `--encoding`, or
  remembered for the file; detection when that does not decode), else detection
  as for an open, or ISO-8859-1 for a binary file's view.
- `LoadReservation.reload` charges the span past the document's undo headroom
  until the UI thread has applied it. A span the budget cannot hold leaves the
  text as it is, with a notice, and so does a failed read. After either, the
  watch takes the checked baseline, so the file is read again, and the notice
  repeats, only on a new change; the save baseline stays, so the next save
  asks.
- A file written in place between the passes fails the reload with a notice,
  and the next check reloads again. A transient read failure retries only on a
  new change.
- A document edited during the read reloads again, at most three times. An edit
  that made a clean document dirty stops the reload: the text stays and the
  change prompt shows, or Reload from Disk's prompt when no check started it.
  An edit undone back to clean reloads. An edit during a reload the user
  confirmed over dirty text is replaced, and undo returns it.
- Reload applies the file's `roundTrips` as an open does. Bytes that do not
  round trip set `bytesGuarded` and read-only, with the open's notice text in
  the status bar, also after the user turned Read-Only off for earlier bytes.
  Bytes that do clear the flag, and clear read-only when the guard alone set it
  (`readOnlyByGuard`); Toggle Read-Only makes the state the user's.

## Prompts

- One prompt per document at a time. A newer change while it is open becomes
  the change the answer acts on.
- Keep Mine takes the file's new baseline for the checks (`watch.baseline()`,
  which recovery records as a buffer's base) but keeps the save baseline
  (`OpenFile.baseline`), so the next save asks. Closing the prompt keeps.
- The save conflict prompt replaces the save's notice: Overwrite writes without
  the baseline check, Reload and Compare act as in the change prompt, and
  Cancel leaves the document dirty.

## Compare

`CompareController` opens the read-only comparison. Compare Documents (View >
Compare) asks for another open document; Compare with File on Disk and the
prompts' Compare put the file on disk left of the buffer.

- The texts are snapshots; the file is read in the charset a reload would use.
- `Comparisons` builds the rows with `LineDiff` on a `qcaret-cpu-` worker.
- A side over 100,000 lines or 16 Mi chars gets a notice instead
  (`Comparisons.MAX_LINES`, `MAX_CHARS`); a line over 10,000 chars is compared
  whole and shown cut.
- A `Comparison` holds both sides' rows (a line on one side only faces an empty
  filler row), each row's kind and the first row of each run of changes.
