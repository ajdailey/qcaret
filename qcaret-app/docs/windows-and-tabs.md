# Windows and tabs

`WindowSet`, `ApplicationController`, quit and tab moves, in `app.controller`
of [qcaret-app](../README.md).

## Window set

`WindowSet` holds the main windows of an instance and what they share:
`Settings`, the theme, the keymap, `GrammarLibrary`, the Recent Files list, the
`MemoryBudget`, the recent log lines, the debug log file, `SessionController`
and `RecoveryController`. It is confined to the UI thread, except `isQuitting`
and `addFinishListener`. Each window is an `ApplicationController` over its own
`MainView`, created through `WindowSet.Factory`, implemented in the launcher.
`ApplicationController`'s other constructor makes a set of its own.

`ApplicationController` runs one window's commands and owns its
`DocumentController`, `HelpController`, `FindController`, `SearchController`,
`WorkspaceController`, `NavigationHistory` and `RecentFiles`. It is confined to
the UI thread; settings writes run on `qcaret-lifecycle-` workers. Edit
commands from the menu go to the selected editor's `EditorController`. Once the
window stops, `execute` does nothing.

- Settings: `WindowSet` hears a change first and updates the keymap and the
  theme once; then each window's `apply` updates its own controllers and view.
  A closed window leaves `Settings` and `GrammarLibrary`.
- Window menu: New Window (an untitled document, the remembered size), Move to
  New Window, and with several windows one entry each. A window's activation
  makes it the target of handed files.

## Handed launches

`openHandoff` opens a handed launch in the window activated last, or a new one
with `-n`, and raises it. `DocumentController.open(FileRequest)` applies the
options; `+<line>` and `-r` also apply to a file already open, `-e` only to a
file read now.

While the session restores, a launch (`-n` and `--diff` included) or a macOS
open waits in `WindowSet`, unbounded, and opens in arrival order after the
restore and the command line's files. Each file then reads on its own worker,
so tab order across launches follows open completion. A quit before that drops
the waiting launches with one WARN each, though each client got `OK`; so does a
launch reaching the UI thread after the quit began.

## One buffer per file

When `DocumentOpener` resolves a real path that another window holds, the open
ends unread, that tab is selected and its window raised
(`DocumentController.Elsewhere`). This covers every open, not only handed
files. Two windows opening one file in the same moment can still read it twice,
and two Save As runs onto one new file can both land; the external-change check
then applies to both tabs.

## Quit

Exit (`QUIT`, the macOS Quit) runs `requestQuit`. With `session.snapshot` on
and this instance owning the session it writes the snapshot of every window
(see [Sessions and recovery](sessions-and-recovery.md#quit-snapshot)); during a
restore it waits for the restore's end. Otherwise `QuitPrompts` runs:

1. Asks about every dirty document, windows most recently activated first, tabs
   in order; nothing closes or saves. An answer belongs to the `Document`, so a
   tab moved during a prompt keeps it, and a file handed or a document edited
   meanwhile is asked about. Don't Save holds for the revision it was given on.
2. Runs the Save answers in the same order. A document edited during its save
   is asked about again. A save that writes no file (a failure, a canceled
   chooser, a declined replace prompt, Reload or Cancel at the save conflict
   prompt) stops the quit with that tab selected: the windows stay open, and
   documents already saved stay saved. Compare leaves that prompt open, and the
   quit waits for its next answer.
3. Selects the tabs selected at the start, captures the session, closes every
   document and calls `quit`.

Cancel at any prompt selects those tabs again, raises the window that was in
front and leaves every document as it was. Until a step closes it, a dirty
buffer keeps its recovery file. A second Quit or a window close during the run
does nothing.

A window's close button runs `requestClose`, which asks about that window's
documents and closes it alone; the last window quits instead, and a further
window stays open while the session restores.

`quit` asks nothing and runs once: it captures the session, queues the last
recovery writes, stops every window, records the normal bounds and maximized
state of the window activated last, hides the windows, then on a worker runs
the quit listeners, saves the settings and the session, finishes recovery,
closes `Workers`, runs the finish listeners and posts the disposals. With
`rememberWindow` false (a `--window-*` option or `-m`) it records nothing.

## Tab commands

- Close to the Left and Close to the Right (`TabInput.closeSideRequested`)
  close that side's tabs in tab order, asking for each dirty one; Cancel stops
  there, and the clicked tab is selected only when the selected tab closed. The
  `CLOSE_LEFT` and `CLOSE_RIGHT` commands of File > Close Multiple Tabs
  (`DocumentController.closeSide`) do the same from the selected tab.
- Close All and Close Others ask for each dirty document in tab order and stop
  at Cancel. Next and Previous Tab wrap around.
- Open Containing Folder checks the folder on a worker and shows a notice when
  it is gone; unlike Open Settings Folder it creates nothing.
- `EditorInput.menuFromView` keeps the selections when a right click lands in a
  non-empty one, ends included, and otherwise leaves one caret at the click.

## Last tab

`LastTab` applies to a user's close of a window's last tab: File > Close, the
tab's close button or middle click, Close All and Close Others. With
`tabs.closeWindowWithLastTab` off, an untitled document opens in the window,
selected. An untitled document with no text and no unsaved changes stays open
when it is the only tab, so closing it does nothing and Close All keeps it when
it comes last. With the option on, the window closes through `requestClose`,
and the last window quits.

A tab move, a window close and a quit close documents without `LastTab`. A
window left by a move stays open, empty. No untitled document opens while a quit
asks, writes its snapshot or runs (`WindowSet.quitRunning`), so none reaches the
session.

## Tab moves

`WindowSet.moveDocument` moves a tab with its live `Document` to another window
or a new one; nothing is read again. `EditorController`, `DisplayProjection`,
`SelectionModel`, `Bookmarks` and `SyntaxHighlighter` stay and get new views,
so undo history, dirty state, carets, folds, bookmarks, grammar, encoding, line
endings and the read-only flag go with them. Scroll is read from the old view
(`EditorView.topRow`) and set on the new one on the next UI turn
(`scrollToRow`). The buffer id goes with the document, so its recovery file and
written revision stand.

The source's `OpenDocument` ends as closed, so a prompt or a late grammar
detection holding it drops out, and the target builds its own around the same
objects: the editors' callbacks, clipboard and overview are connected again,
and the file watch moves to the target's `ExternalChanges`. The two entries
share one `OpenFile`, the document's file link: paths, the save baseline, the
forced charset, the watch, the missing and round-trip state and the running
save and reload. A late result of the source's entry compares its token and
`closed` before it writes there, so a canceled save's writer ending after the
move leaves the target's state alone. `discard` and `moveTo` take the entry out
through one `detach`. A window left without a tab stays open, empty.

The move is refused with a notice while the tab's save, conversion or reload
runs, or its change prompt shows, because each delivers to the source's entry;
and when the target holds the same file. Nothing moves while quitting.
`addMoveListener` runs once per move; `SessionController.changed` is one
listener.

A running line transform (sort, duplicate removal, trim) or `ReplaceRun` does
not hold the move back. Both deliver through objects that move: the transform
through its `EditorController`, the run through `DocumentController.holderOf`.
A result lands in the moved document at the revision it was computed from;
after an edit in the target it is dropped, with `LINES_STALE` or a skipped row.

Search results stay in the source's dock: `DocumentController.holderOf` finds
the window holding a result's document, so a result selects the tab there and
raises that window, and Replace reaches the document in any window. A document
that closes in any window detaches its result groups in all of them
(`SearchController.documentClosed`). A folder search reads the disk copy of a
file another window holds, as it reads a file no window holds. Navigation
history entries of a moved document turn into file positions, which then lead
to its tab through the one-buffer rule.
