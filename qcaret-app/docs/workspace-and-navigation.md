# Workspace and navigation

The workspace folder and browser, Quick Open, Go to Symbol, navigation history
and Recent Files: `WorkspaceController`, `QuickOpen`, `SymbolRows`,
`NavigationHistory` and `RecentFiles` in `app.controller` of
[qcaret-app](../README.md). The listing and index walks are in [qcaret-core
search](../../qcaret-core/docs/search.md#workspace-folder).

## Workspace folder and browser

File > Set Workspace Folder stores one folder per window, kept by the session,
as its real path (`WorkspaceController`). The index and the browser leave out
hidden entries and `workspace.excludedDirectories`; a change of
`workspace.excludedDirectories` lists and indexes the folder again.

- View > Workspace Browser shows the folder's tree. A directory is listed on a
  `qcaret-io-` worker the first time it expands (`DirectoryListing`), 500
  entries at a time behind a "Show N more" row. A link to a directory that
  contains it lists as a row that does not expand.
- Each row carries its Size and Modified texts, built with the listing on the
  worker (`BrowserRows`), so no row reads the disk on the UI thread: a file's
  size in B, or KiB, MiB or GiB with one decimal, and the date in the default
  locale's short date format and the default time zone, with the short date
  and time as the cell's tool tip (`WorkspaceNode.modifiedTime`). A directory
  has no size.
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

## Browsing other folders

`WorkspaceBrowse` shows another folder in the tree without changing the
workspace folder, which stays the scope of the index, Quick Open and
HyperSearch's Workspace folder. A shown folder outside it takes the row menu's
file operations and Reveal Active File; the index changes only for an
operation under the workspace folder.

- The path field takes an absolute path, a path relative to the shown folder,
  or `~` for the home folder. Parent shows the shown folder's parent, Home the
  home folder, and Workspace the workspace folder again. Home is `user.home` as
  the JVM started (`HomeFolder`): in explicit and portable mode on Linux the
  launcher points `user.home` at the settings directory and keeps the first
  value in `qcaret.userHome`.
- A folder is checked and resolved to its real path on a `qcaret-io-` worker;
  a later request drops an earlier one still being checked. A missing path, a
  file, or a path that does not parse leaves the tree as it is and shows a
  notice. A shown folder that cannot be read lists as its error row.
- A new shown folder drops every listing; a listing still running is canceled
  and its result dropped.
- Favorites are `workspace.favorite.N`, shared by every window: Add Shown
  Folder, Remove Shown Folder, and a favorite opens like a typed path.
- The filter (`NameFilter`, such as `*.java;*.md`) applies to files, never to
  directories, and ignores case on Windows and macOS. The Hidden check box
  lists hidden entries; `workspace.excludedDirectories` stays out either way.
  A header click sorts by name, size or modified time, directories first; a
  second click reverses the order. A filter, hidden or sort change lists every
  listed directory again, keeping the old rows until the new ones arrive; a
  filter that does not parse shows a notice and changes nothing.
- Set as Workspace Folder on empty space makes the shown folder the workspace
  folder.
- The session keeps the shown folder, the filter and the Hidden state per
  window; the sort is not kept. `WorkspaceBrowse` holds the listing options and
  restores the session's folders, resolved on a `qcaret-io-` worker.

## Browser file operations

`WorkspaceFiles` runs the row menu's Search in Folder, New File, New Folder,
Rename and Delete. Rename and Delete apply to a row below the shown folder,
New File and New Folder to a directory row or the shown folder itself (the menu
over empty space). Names are checked on the UI thread, files are touched on
`qcaret-io-` workers (`FileOperations` in qcaret-core), and no operation follows a symbolic
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
- A rename of the workspace folder or a parent moves the workspace folder and
  a folder browsed to under the entry in every window
  (`WorkspaceFollow.follow`), with a notice: the index is built again
  under the new path, the browser lists it again with its expanded
  directories, and the session is saved.
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
  changes](external-changes.md)). A finished delete of the workspace folder or
  a parent clears the workspace folder in every window with a notice
  (`WorkspaceFollow.clearUnder`), and a folder browsed to under the entry
  with it; a delete that stops partway leaves the workspace folder set.
- Rename and Delete refuse, with a notice, while a save, a reload or a change
  prompt holds a document under the entry.
- After each operation the directory is listed again, the listings under a
  removed folder are dropped, and the new row is selected. The same happens in
  every other window's browser that lists the directory, without the
  selection (`BrowserWindows`); a Refresh lists its directories again in the
  other windows too. One file created, renamed or deleted under a window's
  workspace folder changes its index in place (`FileIndex.withChange`); a
  folder or a link, a cut index or a build in progress builds it again.

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
- A query with `@` matches symbols ([Go to Symbol](#go-to-symbol)). `@text`
  lists the selected document's symbols; `file@text` matches files by the text
  before the first `@` and, on open, moves the caret to the symbol that `Fuzzy`
  scores highest for the text after it, the first among equals, or shows a
  notice when none matches.
- An index of up to 2,000 files is matched on the UI thread, a larger one on a
  `qcaret-cpu-` worker canceled by the next query or the popup's close. An
  index build that ends while the popup shows matches the query again.

Window > Switch Document is the same popup over the open documents only.

## Go to Symbol

Edit > Go to Symbol (Mod+R) opens Quick Open with `@` typed (`initialQuery`).
`SymbolRows` lists the selected document's symbols that `Fuzzy` matches on the
name, best first and by line among equals, at most 500; an empty query lists
them by line. The detail column is the kind and the line.

- `DocumentSymbols` scans the document on a `qcaret-cpu-` worker with
  `SymbolScanner` ([Symbol rules](../../qcaret-core/docs/grammars.md#symbol-rules)).
  A document whose highlighter has no grammar, plain text or one still being
  detected, is scanned with the grammar `GrammarSet.detect` picks for it.
- The popup scans once and its close cancels the scan. A result read at an
  older revision than the document's is dropped and the scan runs again, at
  most three times; the summary then says the document kept changing.
- Choosing a row records the caret for Go Back (`EditorController.jumping`) and
  moves it to the name's start, the line and column clamped to the current
  text.
- `file@symbol` scans after the file opens. The jump is dropped when the
  document changed, the caret moved or the editor closed during the scan.

## Navigation history

`NavigationHistory` keeps Go Back and Go Forward's caret positions (Search
menu), at most 100.

- A position is recorded where the caret was before a jump: Go to Line, Next or
  Previous Bookmark, a Bookmarks list row, a search result, Quick Open, Go to
  Symbol (`EditorController.jumping`), and a change of the selected document,
  which records the caret of the document left.
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
