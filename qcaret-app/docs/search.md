# Search and replace

The find bar, HyperSearch and replace across files: `FindController`,
`SearchController` and `ReplaceController` in `app.controller`, and
`app.search`, of [qcaret-app](../README.md). The matcher, the file walk and
`FileReplace` are in [qcaret-core search](../../qcaret-core/docs/search.md).

## Find bar

`FindController` runs the find bar against the selected document.

- Find opens the bar; a selection within one line of up to 1,000 chars becomes
  the search text. Typing finds from where the selection started when the bar
  opened, or from the last match found.
- Find Next and Previous search from the focused view's selection to the end
  (start), then wrap with a notice; with no text they open the bar.
- While the bar is open, each view of the selected document highlights the
  matches in its visible range, at most 5,000. A text change shifts the shown
  highlights at once, dropping those it touches, and recomputes them.
- Replace replaces the selection when the query matches exactly that range,
  then finds the next match; Replace All replaces every match. Each is one undo
  step, line breaks in the replacement become the document's line ending, and a
  read-only document disables both (`FindState.replaceEnabled`). A Replace All
  result dropped because the document changed shows a notice.
- Esc in a view with one caret and no block closes the bar and removes the
  highlights; with extra carets or a block, Esc drops those first
  (`EditorController.cancel`, `DocumentController.ViewListener.escapePressed`).
- Every match is computed on a `qcaret-cpu-` worker from a snapshot; a newer
  request cancels the running one. A result carries its generation and revision
  and is dropped when either is stale. An invalid regex, a stack overflow and
  an invalid replacement end with a notice in the bar.

### Search history

Find Next, Find Previous and closing the bar put a query that compiles first in
`UiState.texts`, the list HyperSearch reads. Replace and Replace All also put
the replacement first in `UiState.replacements`. Typing saves nothing.
`FindState` carries both lists; `ApplicationController.apply` updates an open
bar when another source changes them.

## HyperSearch

`SearchController` runs HyperSearch (Search > HyperSearch) and is the results
dock's `ResultsInput`. The dialog opens with a one-line selection of up to
1,000 chars as its text, or the selection scope for a selection over several
lines, and the last run's options. Texts, directories and include masks are
kept in `UiState`, 20 each, up to 1,000 chars an entry; the texts and the
toggles are shared with the find bar. A request without text, with a regex or
mask that does not parse, without a document, selection or workspace folder for
its scope, or without a directory shows a message instead.

| Scope | Reads |
|---|---|
| Current document, Selection | The selected document's snapshot; Selection only inside the focused view's selections |
| Open documents | Every open document's snapshot, binary views left out |
| Workspace folder, Directory and subdirectories | The folder's tree through `FileSearch`; Directory reads its files only. The workspace browser's Search in Folder opens the dialog on Directory and subdirectories |

### Open documents in folder scopes

Open documents are searched from their snapshots in every scope, so a folder
search reads a dirty buffer as edited and not its file, in any window; the Open
documents scope reads the source window's tabs. The match is by real path: a
document holds its file's real path (`SafePaths.userFile`, and Save As records
the real path), and a search root is resolved to its real path on a
`qcaret-io-` worker before the search starts, when the dialog submits a
directory or tree scope and when the workspace folder is set. A rerun uses the
stored root. Without that, a folder reached through a link (macOS `/var`) or a
Windows short name holds no open document, the walk reads the file on disk, and
a link at the root is not walked at all. A root that does not resolve keeps its
absolute path and reports as unreadable.

### Result sets

Each run is an `app.search.HyperSearch` filling a `ResultSet`:

- Documents are matched on `qcaret-cpu-` workers, one task each; a folder is
  walked from a `qcaret-search-` worker. Workers queue file groups
  (`FileResult`), and the UI thread moves them into the set at most every 100
  ms, then calls `resultsChanged`; open documents come first in tab order,
  files by path, once the search ends.
- At most 100,000 matches per set (`HyperSearch.Limits`): the group crossing
  the limit is cut, and a source left unsearched for lack of room ends the set
  truncated. So does a group whose heap (`FileResult.bytes`, 96 bytes per match
  plus its snippet) the `MemoryBudget` refuses. A dropped set returns its
  reservation, groups that arrived after the drop included.
- Files over 64 MiB, binary files and unreadable files are skipped and counted;
  a line over `search.maxLineChars` (1,000,000 chars by default) is skipped and
  counted. A regex stack overflow ends that source and names its line in its
  group row.
- Stop ends a set canceled with what arrived. Rerun runs the request again
  while the set shows its results; they are replaced when the rerun ends done
  or truncated, and kept, with a notice, when it is stopped.
- Sets are listed newest first; past ten unpinned sets the oldest unpinned one
  is dropped. Pin keeps a set; Clear and Clear All stop and drop sets.

### Stale matches

A document group maps its matches to the current revision through
`Document.positions` (`FileResult.rebase`, on each row, navigation and overview
read); a match whose text then differs in length or hash is stale, and past the
position log the group is stale. A file group keeps the file's `FileStamp` and
SHA-256; expanding it or opening one of its matches checks the stamp on a
`qcaret-io-` worker, and a changed stamp whose bytes hash differently, read in
1 MiB blocks, marks it stale. A closed document's groups keep their offsets and
open the file from then on.

Opening a match selects it only when the text there has the searched length and
hash; otherwise the match is marked stale, the caret goes to its line and a
notice says so. Next and Previous Result step through the set last navigated,
or the newest set, wrapping at its ends.

Rerun File (dock) searches one stale group's source again with the set's query
(`app.search.FileRerun`): an open document from a snapshot, a file from its
bytes. The new group takes the old one's place and may use the matches the
set's limit leaves free; a source without matches leaves the set. A selection
set reruns whole, since the selection is not kept.

## Replace across files

`ReplaceController` replaces the matches of a finished set (dock Replace, or
Search > Replace in Results, Mod+Shift+H, on the set last navigated or the
newest set with matches). Every scope goes through the same preview and run.

- The preview lists the set's groups with matches, every file and match
  checked, and the replacement text (a regex query's `$n`, `${name}`, `\n`,
  `\t`; a literal query's text as is). The selected file's before and after
  view (`app.search.ReplacePreview`: changed lines, two lines of context,
  regions diffed by `core.diff.LineDiff`, at most 2,000 lines) is computed on a
  worker from the open document's text or the file's bytes, again on each
  change of the text or the checks. A file Apply would skip shows why.
- Apply starts an `app.search.ReplaceRun` and opens its report; one run or
  restore at a time. An open document is replaced in only at the revision
  searched, as one `EditBatch` and one undo step, and stays dirty. A file group
  whose file is open now is edited as its buffer while the buffer's text,
  encoded, has the SHA-256 searched. Replacements are computed on the worker
  from a snapshot and applied on the UI thread, which skips the document if it
  changed, closed or became read-only meanwhile.
- A file not open goes through `FileReplace` on up to four `qcaret-io-`
  workers, with its original bytes in the run's `ReplaceBackups` under
  `<settings>/replace-backups/<run>/`; each run first deletes runs older than
  `replace.backupDays`. Cancel (the report's Stop) stops between files.
- The report lists each file as replaced, skipped with the reason, failed with
  the error, or not attempted. When the run ends, each file group it changed is
  marked stale.
- No undo spans files: Restore Run in the report, or Search > Restore Replace
  Run, writes a run's backups back, refusing a file changed since the run. An
  open document whose file a restore wrote is checked at once: a clean one
  reloads, a dirty one asks.
