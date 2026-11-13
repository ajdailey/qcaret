# Documents

Opening, loading, saving, encodings and printing of one window's documents:
`DocumentController`, `DocumentOpener`, `SaveAndReload` and `PrintController` in
`app.controller` of [qcaret-app](../README.md).

`DocumentController` holds the window's documents, one tab each, as
package-private `OpenDocument` entries: the document, its `OpenFile` file link,
editors, tab, grammar and declared settings. `DocumentSettings` (per-file
settings), `DocumentsUnder` (workspace browser Rename and Delete),
`DocumentGrammars` (grammar detection and choice), `DocumentEncodings` (Save
with Encoding, line-ending conversion), `TabClosing` (close order and the
unsaved prompt) and `DocumentSessions` (session tabs, remembered file state,
the closed-file list) take the entries directly. An entry reaches its window only through its constructor
arguments, the window's `MainView` and selected entry. `add` gives
every document `editor.tabWidth` and `editor.insertSpaces`; a remembered file
state or a session tab then replaces them, then the file's declared settings
([Per-file settings](#per-file-settings)), and a settings change leaves open
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
  reopen of a document runs at a time. The load replaces its own tab, not
  another tab on the same path, and keeps the path the file was opened by, so
  a file opened through a link keeps the link.
- `reopenWithDetection` does the same with detection, past a remembered
  charset, and leaves the document's `forced` charset null, so the file state
  and the session hold none.
- Save with Encoding changes only what the next save writes and makes the
  document dirty; the byte order mark is dropped for another charset.
- A line-ending conversion builds the new text on a `qcaret-cpu-` worker
  (`LineConversion`) and applies it as one whole-text replacement and one undo
  step, then makes the ending the one Enter inserts. A result whose document
  changed or closed meanwhile is dropped. Mixed endings are otherwise kept.

## Per-file settings

`FileSettingsReader` reads what a file declares in `.editorconfig` files and
modelines ([Per-file
settings](../../qcaret-core/docs/files-and-encodings.md#per-file-settings)),
from the sources `editor.editorConfig` and `editor.modelines` turn on;
`ApplicationController` turns it on with `setFileSettings`. Each
`.editorconfig` that contributes nothing logs one WARN per read.

On open, `DocumentOpener`'s `qcaret-io-` worker resolves the EditorConfig
properties before the load, passes their charset as `LoadOptions.configured`,
and reads the modelines from the loaded text; `LoadedFile.settings` carries the
result to the UI thread.

| Setting | Order, first wins |
|---|---|
| Charset | A charset the user forced (Reopen with Encoding, `-e`, Save with Encoding, the one remembered for the file or the session tab); a BOM; a modeline or in-file declaration; EditorConfig; detection |
| Width, spaces or tabs | Modeline; EditorConfig; the remembered file state or session tab; `editor.tabWidth` and `editor.insertSpaces` |
| Ending Enter inserts | Modeline or EditorConfig, only while the text has no line break; the most frequent ending; LF |

`end_of_line` converts no existing ending, so a CR LF file in an `lf` project
stays CR LF and Enter inserts CR LF. A file that does not exist yet takes the
declared charset, ending and indentation once a worker has read them, while it
is empty and clean.

The tab's tool tip adds a line naming the `.editorconfig` files and the
modeline kind when any contributed.

| Event | Settings read again | What changes |
|---|---|---|
| Save As to another path | For the target, on the worker that resolves its real path | Width, spaces, Enter's ending, trim and final newline, tool tip, applied once the write succeeds; the encoding written stays |
| Reload, external change | For the file, on the reload worker | The same, from the reloaded text; the EditorConfig charset joins detection |
| Rename from the workspace browser | For the new path, on a `qcaret-io-` worker; dropped when the document moved again meanwhile | As Save As, applied when the result arrives |
| Reopen with Encoding | As an open | Everything an open sets; the chosen charset stays forced |

### Save cleanup

With `trim_trailing_whitespace = true` or `insert_final_newline = true`, a save
first computes `SaveCleanup.edits` from a snapshot on a `qcaret-io-` worker:
spaces and tabs at each line's end removed, then the document's line ending
appended to a text that does not end with a break. The UI thread applies the
edit as one undo step and the write takes the snapshot after it, so the bytes
written are the text shown. An edit made while the cleanup ran drops it and
computes it again, up to 3 times; then the write goes on without it. Esc or a
close during the cleanup ends the save unwritten. `false`, a missing property
and a read-only document leave the text alone. Blanks whose removal would join a
CR and an LF stay, and the editor's notice counts their lines.

## Saving

`DocumentController` routes Save, Save As, Save All, the quit saves and reload
to `SaveAndReload`, one per window, which hands reload to `FileReload`.
`SaveAndReload` calls `DocumentSettings.prepareSave` for the save cleanup and
`DocumentsUnder` for the rename hold.

Save writes a `DocumentSnapshot` through `DocumentFiles.save` on a `qcaret-io-`
worker while editing goes on, then calls `markSaved`.

- An untitled document asks for a file first. A save while one runs for the
  same document shows a notice instead.
- Esc in the editor and closing the tab cancel a running save. A cancel before
  the move leaves the target unchanged; an in-place write that has begun runs
  to its end and reports saved. The next save of the file waits for it
  (`OpenFile.writing`) and its result follows that write's; a conflict whose
  stamp is the one that write recorded writes again without asking.
- A file without write permission fails with `SAVE_FAILED`.
- Each running Save, Save As, Save a Copy As and checksum list Save As lists
  its target in `RunningWrites` until its result reaches the UI thread; a
  workspace Rename or Delete of a folder holding one refuses.
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

### Save a Copy As

`SaveCopy` writes the document's snapshot through `DocumentFiles.save` on a
`qcaret-io-` worker, with no baseline. The copy takes the document's encoding,
BOM and line breaks; when the document's declared settings ask for the save
cleanup, `SaveCleanup.edits` is applied to a `RopeStorage.from` copy of the
snapshot, so the document's text, undo history and dirty state are untouched.
The document keeps its path, file check and session entry, and the copy is not
opened.

- A target whose real path is the document's file runs Save, and so does a
  hard link of it (`Files.isSameFile`, on the resolving worker): a copy would
  write the document's own inode in place, outside `OpenFile.writing` and with
  its file check running.
- A written copy is checked at once in the tab holding that file, in any
  window, which takes it as an external change, and goes to the top of Recent
  Files.
- A failed write leaves the target unchanged and shows `SAVE_FAILED` with the
  target's name. Only Esc cancels the write (`SaveCopy.cancel`); a second copy
  of the same document while one runs shows a notice.
- Closing the tab, Don't Save and closing the window leave the write running,
  so a copy followed by Don't Save keeps the edits in the copy. The result
  shows in the tab's window while it is open, otherwise in the active window.
  The tab does not move to another window while the copy runs
  (`OpenFile.inFlight`).
- Quit waits for every running copy, the snapshot quit included
  (`RunningCopies`). A failed copy stops the quit, as a failed save does.
  After `RunningCopies.BOUND` (30 s) a modal prompt in the active window
  (`MainView.askUnfinishedCopies`) names each copy still running. Quit goes on
  and logs the copies at WARN; they may be incomplete. Wait, Esc and the close
  button wait another bound. A copy ending while the prompt shows closes it and
  the quit goes on, or stops on a failure, as before.

Close asks Save, Don't Save or Cancel for a dirty document. Save closes once
the save succeeds, and an edit made during the write asks again, with the tab
and its recovery file kept. A closed document disposes its editor and releases
its heap reservation.

## Other document commands

- Save All saves every dirty document in tab order.
- Reopen Closed Tab opens the file of the last of up to 20 closed tabs again.
- Open All in Recent Files and Recently Closed (`OpenAll`) checks the listed
  files on a `qcaret-io-` worker and passes the readable regular files, in list
  order, to the open, which selects a file already open. The others are
  named in one status notice and get no tab, where a single open of a missing
  file opens an empty tab for it. Recently Closed drops each file once its tab
  shows (`open(Path, Consumer)`), so a skipped file and one whose load fails or
  is canceled stay listed.
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
