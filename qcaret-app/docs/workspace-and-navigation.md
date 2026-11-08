# Workspace and navigation

The workspace folder and browser, Quick Open, navigation history and Recent
Files: `WorkspaceController`, `QuickOpen`, `NavigationHistory` and
`RecentFiles` in `app.controller` of [qcaret-app](../README.md). The listing
and index walks are in [qcaret-core
search](../../qcaret-core/docs/search.md#workspace-folder).

## Workspace folder and browser

File > Set Workspace Folder stores one folder per window, kept by the session,
as its real path (`WorkspaceController`). Both the browser and the index leave
out hidden entries and `workspace.excludedDirectories`; a change lists and
indexes the folder again.

- View > Workspace Browser shows the folder's tree. A directory is listed on a
  `qcaret-io-` worker the first time it expands (`DirectoryListing`), 500
  entries at a time behind a "Show N more" row. A link to a directory that
  contains it lists as a row that does not expand.
- Refresh lists every listed directory again, keeping the old rows until the
  new ones arrive, and rebuilds the index.
- Reveal Active File lists each directory down to the selected document's file,
  shows enough entries to hold it and selects it.
- A file row opens on activation; the row menu offers Open in Default
  Application, Set as Workspace Folder and Copy Path, then the file operations
  below.
- Open in Default Application refuses a file the system would run: on Windows
  by extension (`Files.isExecutable` is true for every readable file there),
  elsewhere by the executable bit, read on a worker.

## Browser file operations

`WorkspaceFiles` runs the row menu's Search in Folder, New File, New Folder,
Rename and Delete. Rename and Delete apply to a row below the folder, New File
and New Folder to a directory row or the folder itself (the menu over empty
space). Names are checked on the UI thread, files are touched on `qcaret-io-`
workers (`FileOperations` in qcaret-core), and no operation follows a symbolic
link or a Windows junction.

- Search in Folder opens HyperSearch with Directory and subdirectories set to
  the row's directory, or a file row's parent; the user still presses Search.
- A name must be one file name: not blank, `.` or `..`, and without `/`, `\` or
  NUL, on every platform; `SafePaths.userFile` then rejects a Windows device
  name. A taken name is refused, so nothing is replaced. New File opens the new
  file in a tab.
- Rename keeps the entry in its directory. A name differing only in case from
  the same entry on a case-insensitive file system renames through a temporary
  name, since `Files.move` returns without renaming when source and target are
  one file; a distinct entry with that name is refused.
- Rename moves every open document under the entry, in every window
  (`PathMove`, applied by `DocumentsUnder`): real path, opened path, watch, tab
  and window titles, grammar detection, per-file settings ([Per-file
  settings](documents.md#per-file-settings)), Reopen Closed Tab and Recent
  Files entries, and the remembered state of closed files (caret, encoding,
  grammar).
  The baseline and the unsaved changes stay, and the session is saved. A failed
  rename changes nothing.
- From the moment the name is accepted until the move lands or fails, the
  documents under the entry are held in every window: their checks stop, so a
  check that finds the source path empty marks nothing missing, and Save, Save
  As onto a path under the entry and Reload refuse with a notice. A save in that
  window would write the source path, mark the document clean, and leave the
  edits in a recreated file under the old name. A recovery write in the window
  records the source path and is written again with the new one at the next
  interval.
- Delete is permanent, with no trash. It asks first, Cancel the default: the
  prompt names the file, the link (its target stays) or the folder with its
  file and folder counts, counted on a worker, says it cannot be undone and
  lists the open documents under it with unsaved changes. A folder is deleted
  children first and stops at the first entry that fails, reporting the entry,
  the reason and what is left. Open documents under the entry stay open and are
  checked at once, which marks a deleted file missing (see [External
  changes](external-changes.md)).
- Rename and Delete refuse, with a notice, while a save, a reload or a change
  prompt holds a document under the entry.
- After each operation the directory is listed again, the listings under a
  removed folder are dropped, and the new row is selected. One file created,
  renamed or deleted changes the index in place (`FileIndex.withChange`); a
  folder or a link, a cut index or a build in progress builds it again. Another
  window's browser shows the change on its next Refresh.

## File index

The file index (`FileIndex`, at most 200,000 files, charged to the
`MemoryBudget`) is built on a `qcaret-search-` worker when the folder is set
and on Refresh; a new build cancels the running one. A folder change drops the
old index at once; Refresh keeps it until the new one arrives. A dropped,
replaced or stale index and window close return its charge.

## Quick Open

File > Quick Open (`QuickOpen`) lists the open documents that match, in tab
order among equals, then the index's files, at most 100 rows.

- `Fuzzy` scores the file name; a file whose name does not match is scored on
  its path below the folder and ranks after every name match; equal scores put
  the shorter path first.
- A file open in a tab is listed once, as the document.
- A typed query also matches the Recent Files neither open nor in the index,
  after the workspace files, without a folder too. A link inside the folder is
  indexed under its own path and can list beside its target.
- An empty query lists the open documents in tab order, then the Recent Files
  not open, newest first, and the summary counts the indexed files. Both lists
  hold real paths, so a file shows once.
- A row's detail is the path below the folder, the whole path outside it, or
  empty for an untitled document; `QuickOpenRow.open` marks an open document.
- A query ending in `:line` or `:line:column` opens there.
- An index of up to 2,000 files is matched on the UI thread, a larger one on a
  `qcaret-cpu-` worker canceled by the next query or the popup's close. An
  index build that ends while the popup shows matches the query again.

Window > Switch Document is the same popup over the open documents only.

## Navigation history

`NavigationHistory` keeps Go Back and Go Forward's caret positions (Search
menu), at most 100.

- A position is recorded where the caret was before a jump: Go to Line, Next or
  Previous Bookmark, a Bookmarks list row, a search result, Quick Open
  (`EditorController.jumping`), and a change of the selected document, which
  records the caret of the document left.
- A jump in the turn a document was selected records nothing more, so opening a
  result in another document adds one position. Ordinary caret moves record
  nothing.
- A position in an open document is an `Anchor` and follows edits. When the
  document closes it becomes the file's line and column, and an untitled
  document's positions are dropped.
- Going to a closed file's position checks on a `qcaret-io-` worker that the
  file exists, then opens it; a missing file's position is dropped with a
  notice and the move goes on.

## Recent Files

`RecentFiles` is File > Recent Files: the last 20 files opened, newest first,
once each by real path, so a file opened through a link and through its target
is one entry. The session file keeps it. Clear Recent Files empties it.
