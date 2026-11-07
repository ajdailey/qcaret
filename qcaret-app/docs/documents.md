# Documents

Opening, loading, saving, encodings and printing of one window's documents:
`DocumentController`, `DocumentOpener` and `PrintController` in
`app.controller` of [qcaret-app](../README.md).

`DocumentController` holds the window's documents, one tab each. `add` gives
every document `editor.tabWidth` and `editor.insertSpaces`; a remembered file
state or a session tab then replaces them, and a settings change leaves open
documents alone. Loads, saves and conversions each count as one view activity
until their worker ends.

## Opening

New opens an untitled UTF-8 document ("Untitled N"). Open goes through
`DocumentOpener`:

- A missing file opens an empty document for its path.
- A file already open selects its tab. The match is by real path, resolved on
  the opener's worker and looked up (`find`) on the UI thread before the file
  is read, so a file reached again through a link or a short name selects its
  tab unread.
- A document whose text does not encode back to the file's bytes opens
  read-only with a notice (`bytesGuarded`), and Save refuses it with a notice
  while it stays read-only. Save As writes a converted copy, and clearing
  Read-Only lets Save convert the file.
- A binary file opens only after the user accepts the prompt, as a read-only
  ISO-8859-1 view whose tab and status bar say so. Save and Save As refuse it
  in the controller, whichever command path calls them, and the editor refuses
  its edits; the view stays read-only.

### Load progress and cancel

A load still reading after `DocumentOpener.PROGRESS_DELAY_MILLIS` (300 ms)
shows through `DocumentsView.showLoadProgress`: the file's name, or the count
when several run, and the percent of their bytes read. The delay is one
`timerExec`; after it, each `LoadReservation` progress call posts one update at
a time, and only a changed percent reaches the view.

`cancelLoads` cancels every load past `resolved`, hides the row at once and
clears the list, so a load started next gets its own delay. A canceled load
ends in `Listener.canceled` on the UI thread, whichever it reached: the
worker's check, or a finished read still in the queue, which drops the document
and releases the reservation. A session tab counts as done and reports no
failure, so it leaves the next saved session. An open still resolving its path
is not canceled.

### One Open per load

Each open is one `DocumentController.Open`, the listener of that open alone. It
holds the open's callback, restore, forced charset, the path the user gave and
what a reopen replaces, so two loads of one file share nothing and each load
end drops what its open held.

- Every end runs `finishRestore`, because a restore whose `done` never runs
  holds back `recovery.start` and the session save. An opener closed with a
  restore running ends in no call, which happens only at quit, since
  `WindowSet.requestClose` refuses a window's close during a restore.
- The retry without a remembered encoding reuses the object.
- Two opens of one file with callbacks each run theirs at their load's end,
  except that an older open ending after a newer one reached the tab drops its
  callback (`OpenDocument.landed`), so the caret stays at the last place asked
  for.

### DocumentOpener

`DocumentOpener` opens files for one window on `qcaret-io-` workers. Each
`open` takes its own `Listener`, which gets one call ending the open, or none
once the opener is closed or the workers refuse the task; `Progress` is the
window's.

1. A worker resolves the path (`SafePaths.userFile`) and posts it to the UI
   thread, where `Listener.resolved` ends the open unread for an open document.
2. Otherwise a second worker estimates the load (`DocumentFiles.estimate`) and
   reserves it from the `MemoryBudget`. A load the budget cannot hold is
   refused unread; a load whose text outgrows the estimate past the budget is
   refused while reading. The notice names the size, the memory needed and a
   `-Xmx` value.
3. A loaded document arrives with its rows (`DisplayProjection.layout`) and its
   reservation lowered to the retained cost.

An open waiting for the UI thread is ended by `close` too, so a task the
dispatcher drops still ends its activity. `close` sets a flag and cancels the
running loads; a load that finishes after it drops its document and releases
its reservation, whether the worker checks the flag before posting or the
posted task checks it on the UI thread, so no document reaches a closed window.
The token `open` returns cancels that open alone, at any step. An unchecked
failure reports `Listener.failed` from a `finally` at the step it hit, so the
progress row and a restoring tab end; a load's `Throwable` goes on to the
worker's handler. `RecoveryController.readSnapshot` ends a tab's restore the
same way.

## Encodings and line endings

`EncodingChoices` lists `TextEncoding.MENU` first, then every other charset.
`Charset.availableCharsets` costs 130 to 229 ms cold (measured in QLogg), so
`ApplicationController.loadEncodings` computes the list once per run on a
worker.

- Reopen with Encoding loads a clean document's file again with the chosen
  charset and replaces the document in its tab once loaded. A failed or refused
  load shows a notice and leaves it open, and so does a missing file: a reopen
  adds no tab. `OpenDocument.reopen` holds the running `Open`, with the
  document, its revision and the load's token: closing the tab cancels the
  load, and an edit or a Save As during the load keeps the document and drops
  the load. A plain open of the file meanwhile selects the tab unread. One
  reopen of a document runs at a time.
- `reopenWithDetection` does the same with detection, past a remembered
  charset, and leaves the document's `forced` charset null, so the file state
  and the session hold none.
- Save with Encoding changes only what the next save writes and makes the
  document dirty; the byte order mark is dropped for another charset.
- A line-ending conversion builds the new text on a `qcaret-cpu-` worker
  (`LineConversion`) and applies it as one whole-text replacement and one undo
  step, then makes the ending the one Enter inserts. A result whose document
  changed or closed meanwhile is dropped. Mixed endings are otherwise kept.

## Saving

Save writes a `DocumentSnapshot` through `DocumentFiles.save` on a `qcaret-io-`
worker while editing goes on, then calls `markSaved`.

- An untitled document asks for a file first. A save while one runs for the
  same document shows a notice instead.
- Esc in the editor and closing the tab cancel a running save. A cancel before
  the move leaves the target unchanged; an in-place write that has begun runs
  to its end and reports saved.
- A file without write permission fails with `SAVE_FAILED`.
- Save writes through the path the user opened, so a symbolic link is followed
  at each save.
- A target changed on disk since the load, the last save or the last reload is
  not written; the save conflict prompt offers Overwrite, Reload, Compare and
  Cancel (see [External changes](external-changes.md)). Save As keeps that
  check when the target's real path, resolved on the save worker, is the
  document's file, so Save As through a link to it is checked too.
- Save As onto a file another tab holds, in any window (by real path, resolved
  on a `qcaret-io-` worker), closes that tab once the write succeeds. When that
  tab has unsaved changes it asks first, and a decline writes nothing. An edit
  in that tab after the question asks Save, Don't Save or Cancel for it once
  the write ends; Cancel leaves two tabs on the file, and the external-change
  check applies to the edited one.

Close asks Save, Don't Save or Cancel for a dirty document. Save closes once
the save succeeds, and an edit made during the write asks again, with the tab
and its recovery file kept. A closed document disposes its editor and releases
its heap reservation.

## Other document commands

- Save All saves every dirty document in tab order.
- Reopen Closed Tab opens the file of the last of up to 20 closed tabs again.
- Read-Only, the tab width and indentation with spaces are per document.
- Go to Line asks for `line`, `line:column` or `line,column`, from 1; other
  input shows a notice.
- Word wrap, Show Whitespace, Show Line Endings and the completion trigger are
  settings for the window's editors.
- Each document has one `Bookmarks`, shared by its views and disposed on close.
- `WordCompletion` keeps one `WordIndex` per open document and builds it again
  on a `qcaret-cpu-` worker when the document's revision moved since the last
  build. `documentClosed` drops the index when the document closes or moves to
  another window.

### Split views

Split shows the selected document in a second view, side by side or one above
the other; with two views, the other arrangement only rearranges them. Each
view is an `EditorController` with its own `SelectionModel`,
`DisplayProjection` (copied from the focused view's rows) and scroll position,
so view state lives per view, not per tab. The focused view is the one menu and
find commands act on. Unsplit closes every view but the focused one; closing
the tab disposes every view.

## Printing

`PrintController` runs File > Print, one job per window; a second request shows
a notice. The command captures a `PrintSource`: a text snapshot, the active
view's selections, the tab width, the grammar and the line-number setting.
Edits made afterward do not reach the job, and word wrap and folds, which are
view state, do not affect it. `MainView.choosePrinter` returns a `PrintTarget`,
or nothing for a canceled dialog; `PrintTarget` is the toolkit seam.

The job runs on a `qcaret-print-` platform thread: `start` measures a page as
columns and rows, `PrintLayout.of` paginates, `page` prints each page in the
chosen range, `finish` hands the job over, and `close` always runs and discards
an unfinished job. Cancel, or closing the window, stops the job at the next
line or page and shows no notice. A `PrintFailure` shows a notice; so do an
empty selection and a range past the last page, which print nothing.

`PrintLayout` (`app.print`, no toolkit) walks the snapshot once and keeps each
page's start, so memory follows the page count; `page` builds rows on demand.
Rules are in its Javadoc. It tokenizes forward from the first line on its own
`Tokenizer`, because a page's first line needs the state before it, so pages
are built in ascending order. `total` in a page is the count of the whole
layout, so a range prints Page 3 of 10.
