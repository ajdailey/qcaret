# qcaret-app testing

## Harness

The controller tests run the UI thread on the test thread through
`ManualUiDispatcher`. `FakeView` calls `ManualUiDispatcher.checkUiThread` in
every method, so a view call from a worker fails the test instead of passing on
the test thread. `ManualUiDispatcher` holds timers of 5 s and over until
`runLongTimers`, because the recovery timer rearms itself. It is in the test
jar for the other modules.

`Harness` builds a controller over `FakeView` with a recent log and a mock
debug log file. `Windows` is a set of `FakeView`s for the multi-window tests.
Tests that need a symbolic link skip where links cannot be created, and tests
of a file without write permission skip where the user writes every file.

## Coverage

### Windows, handoff and quit

| Class | Covers |
|---|---|
| `HandoffTest` | Client and server over loopback in one JVM: options, `-n`, geometry and `--diff` carried; a wrong token, a peer without the token and a listener without the token; a stale endpoint with and without a live lock; a child JVM killed by `destroyForcibly` releasing the lock; 16 launches against a primary before and after its first window; arrival order; `ERR` at `close` and at `WINDOW_BOUND`; a listener that never answers |
| `WindowSetTest` | What the windows share, handoff targets, the one-buffer rule, close and quit |
| `WindowSessionTest` | Launches handed during a restore (tabs, `-n`, `--diff`, a quit) and while the recovery dialog shows; a two-window restore, the capture before a quit's first close, Cancel and a process end at a quit prompt, a moved buffer's recovery file, a snapshot over two windows, the display state, Clear History in every window and in the session file |
| `TabMoveTest` | The round-trip guard and a missing file moving with the tab; a canceled save's writer ending after the move with the target's state and its running save untouched; the state a move carries; refusals; close and quit around a move; a line transform or replace run delivered after the move. The budget returning to its start shows each document released once |
| `DocumentLifecycleTest` | An `OpenDocument` entry across windows: declared settings, untitled name, buffer id and chosen grammar carried by a move; a reopen in the target window; a rename from another window's browser holding Save with the target's notice, then moving the document and reading its new settings; a close during the save cleanup writing nothing |
| `QuitPromptsTest` | Answers before saves, Cancel, each way a save ends the quit, an edit during a save or after Don't Save, an untitled document saved at a prompt, Compare at a save conflict followed by Overwrite or Cancel |

### Sessions, recovery and settings

| Class | Covers |
|---|---|
| `SessionStoreTest` | Round trip, the limits, the `droppedComments` count, malformed, oversized and unknown-version files |
| `SessionControllerTest` | Restore through real files, the comment count shown and cleared, debounced and shutdown saves on racing workers |
| `RecoveryFileTest` | Round trip of a text over every buffer size, surrogates, damage |
| `RecoveryStoreTest` | Liveness by lock, a live child process that read its `owner.lock`, age limits, the original file's state, an unreadable original hiding no buffer |
| `RecoveryControllerTest` | Each lifecycle step, the dialog's choices, failed writes (a non-empty directory in the file's place makes the move throw), the snapshot quit, with `quit` standing in for a process end |
| `ConfigStoreTest` | Two stores merging on one directory, a save that changes nothing, unknown keys, every key's round trip, invalid values, defaults, malformed and other-version files, the `.invalid` copy, the 1 MiB cap with four full histories of escaped text under half of it, a read-only directory, owner-only creation |
| `SettingsTest` | The ordered queue and `refresh`, including a change made during one, a read-only directory, `setFrom` |
| `PreferencesControllerTest` | `PreferencesInput` driving each setting's effect on the running window; Clear History in the file, after Apply, over another process's entries, for HyperSearch's last run and on a read-only directory. The remaining effects sit with their area (`ExternalChangeTest`, `SyntaxHighlighterTest`, `SearchControllerTest`, `ReplaceControllerTest`, `FileMonitorTest`) |

### Commands and Help

| Class | Covers |
|---|---|
| `ApplicationControllerTest` | The theme save and its restart notice, the quit's placement save, the quit that saves nothing, licenses read on a worker, About |
| `CommandDispatchTest` | Every `CommandId` with no document, with a document holding two selections, and with every region collapsed in a split view: none throws on the UI thread or a worker, and none leaves an activity running |
| `CommandStatesTest` | Every command's checked and enabled state with no document, a text document and a binary view; the status fields |
| `ShellCommandsTest` | Every command but the open and close ones on a binary view, with the file's bytes and its folder's listing unchanged; a declined binary prompt, Reopen and Save with Encoding, the line-ending conversion and its undo, a conversion dropped after an edit, the tab commands, the warning marker, Debug Log File with a failing attach, the user guide copy and its fallback, Report an Issue, the zoom bounds |
| `UserGuidePageTest` | The shipped page: every `#` link names a heading anchor, tables converted, images embedded, no other reference, no script; the converter refusing a bad link |
| `LicenseNoticesTest` | The Licenses text names every bundled component at the versions the parent POM pins |
| `KeymapTest` | No stroke bound twice on any platform; the platform key choices (Add Caret, F6, Go Back and Go Forward); the Quick Open keys; the reference filter; the accelerator, editor and window-filter lookups |
| `CommandPaletteTest` | `Fuzzy`, every menu command listed in menu order, cascade titles, keys and enabled state, running only an enabled command |
| `MenuTableTest` | Mnemonics unique per menu |

### Documents

| Class | Covers |
|---|---|
| `DocumentOpenerTest` | A load held in its decoder past the window's close, and a close after a result is posted: neither reaches the listener and both release their reservation; a load over the budget refused unread, and one that outgrows it refused while reading; an unchecked failure at the resolve, the UI step or the read reporting `failed`; a canceled open reporting only `canceled`; an open document ending the open unread; a close ending an open waiting for the UI thread; a cancel while the worker holds in the decoder or after it finished, reporting only `canceled` and releasing the reservation |
| `DocumentControllerTest` | Open, edit and save; Save As for an untitled document; a missing file; a second open of one file; a file opened again through a symbolic link selecting its tab and running the HyperSearch callback; Save As through a link to a file changed on disk opening the save conflict prompt; the unsaved prompt's three answers; `closeAll` stopping at Cancel; word wrap across editors; the heap refusal |
| `DocumentControllerTest` (held work) | A close's save and a Save As held in the encoder with edits meanwhile; a reopen held in the decoder with a close, save or rename meanwhile; a refused reopen and open: each leaves the tabs, the text, the reservation and `canMove` as they were |
| `DocumentControllerTest` (open ends) | Each way an open ends (loaded, unread, refused, canceled, failed) ends its restore and leaves nothing for the next open; two loads of one file keep apart (the last callback places the caret, a close and an open inside one reopen's load keep the open's callback, a plain open during a reopen or a restore leaves the reopen's charset and the session tab's state, a reopen that fails to resolve leaves the remembered encoding); the retry after a remembered encoding keeps the callback and the link, and canceling it ends the restore; an unchecked decoder failure ends a tab's restore with a failed open; a closed document a completion read is collected; a save held in `GatedCharset`'s encoder canceled with Esc and with a tab close leaves the file unchanged, and a second save while it is held shows the notice |
| `SaveRefusalTest` | Save of a file that does not round-trip refused with the file's bytes kept (regression: `ED 40` became `FA 5C`); clearing Read-Only and Save As converting; a file without write permission; a charset without an encoder, reporting the failure and keeping the document dirty |
| `FileCommandsTest` | Reload from Disk (clean, dirty with both answers, deleted), Recently Closed, Automatic Detection past a forced and a remembered charset, Tab Width > Other, the tab defaults against saved state, the title, a drop, Esc on a sort and a comparison |
| `EditingSettingsTest` | Save All, Reopen Closed Tab, Read-Only, the tab settings, the Go to Line prompt |
| `EncodingChoicesTest`, `LineConversionTest` | The charset list; the line-ending conversion |
| `FileSettingsControllerTest` | `.editorconfig` and modeline settings through open, the tool tip, the precedence table over remembered state and Preferences, the save cleanup as one undo step and recomputed after an edit, Esc during it, `false` values, a forced windows-1252 file past three read blocks trimmed and saved, CR, LF, CR LF, mixed and break-free files with `end_of_line`, a BOM over `charset`, a new UTF-16LE CR LF file, Save As to another directory and Reopen with Encoding reading again, each switch off |
| `SaveCleanupTest` | The trim and final-newline table, a text needing no edit |

### External changes

| Class | Covers |
|---|---|
| `ExternalChangesTest` | A truncate and a later write pinned to one report |
| `ExternalChangeTest` | Keep Mine recording the declined file as a buffer's base; a clean document reloaded as one undo step with its caret line kept; a dirty document's prompt, with Keep Mine making the next save ask and Overwrite writing; a file that never holds still reloading anyway; Reload keeping the edits in undo; a newer change updating the open prompt; a deleted file kept missing and dirty until the same bytes return; a missing file saved without a prompt; a touch with the same bytes neither prompting nor blocking the save; five saves never reported; Compare from the prompt and Compare Documents; a retargeted link reloading and saving its new target; a 3 MiB file reloaded; a span the budget refuses, read again only on a new change and asked about at the next save; a failed reload reported once per change; the round-trip guard set and cleared by a reload and kept apart from the user's read-only choice; typing during a reload held in a gate charset (kept with a prompt, undo after accepting, undone back to clean); Reload from Disk; a confirmed reload; a re-encoded file detected on reload and in Compare with File on Disk; a chosen charset surviving a reload; a Compare refused when the file's text outgrows the budget; Reload at the save conflict prompt; a save during a reload showing its notice; a reload edited during every read stopping after `RELOAD_ATTEMPTS` with a notice |

Each check in `ExternalChangeTest` runs on window activation; a case that
asserts nothing was reported waits for two finished check passes requested
after the change.

### Editor

| Class | Covers |
|---|---|
| `EditorControllerTest` | Multi-caret typing as one undo step, Enter's line ending, Backspace and Delete over CR LF and surrogate pairs, cut, copy and paste with line ending conversion and one line per caret, a read-only document, view offsets mapped through virtual breaks, Esc |
| `MultiCaretTest` | Every caret moving and selecting; vertical and page moves at the preferred column; block keys into virtual space with padded typing undone as one step; a block from the view's columns with copy and cut; column selection mode; add caret above and below and the click toggle; Add Next Occurrence from a worker; line commands whose undo restores every caret; paste per caret; composition text over a selection, over a wrapped row and after a model clear, with a commit as one undo step; block mode with the block's lines gone; Go to with a tab width change |
| `EditorRandomTest` | 1,000 fixed seeds of 300 random commands, compositions and block selections on mixed line endings and surrogate pairs, on a fake clock, with wrap on every third seed and a second view: the change events replayed and selections, surrogates, line ending counts and display rows checked after each step, then everything undone and redone. `-Dqcaret.editor.seed=N` runs one seed |
| `SplitViewTest` | Two views with their own selections over one text, the arrangement switch, focus, Unsplit keeping the focused view, both views stopped with the tab and reached by word wrap |
| `ProductivityTest` | Typed pairs, type-over and pair delete at two carets; a block typing plainly; a sort on a worker as one undo step with the view busy until it applies; a line transform dropped with a notice after an edit; bookmarks following an inserted break with wrap-around navigation; Go to and Select to Matching Bracket at every caret, past the UI-thread bound and through a scroll; the bracket and occurrence highlights with their stale results dropped; completion (words of another open document, the list narrowing, arrows, Esc, Enter, the single-word insert, the automatic trigger) |
| `BookmarksTest` | Bookmarks and comments through edits: an insert above, a deleted line and last line restored by undo and removed again by redo, a join keeping the upper bookmark, a cleared line, moved and sorted lines, coalesced deletes undone; the comment bounds, wrap-around, `LineEnds` |
| `BookmarkListTest` | Edit Bookmark Comment (cleaned text, an empty answer, a cancel, a refusal, the prompt's line followed through an edit); the list following an insert, undo, a tab change and close; Go To into a collapsed region; Edit Comment and Remove from the list; the row limit, text cut and a budget refusal |
| `OverviewTest`, `OverviewMarksTest` | Find, HyperSearch and bookmark marks recomputed after an edit, a hidden strip computing nothing; the line and row mapping |

### Display projection and folding

| Class | Covers |
|---|---|
| `DisplayProjectionTest` | The projection against a display text built line by line from `RowBreaks` after 1,500 seeded random edits that join and part CR LF pairs, wrap on and off, each display change replayed on a mirror string; stale and superseded rewraps, the large-change fallback, one change over distant edits, a rewrap on a tab width change |
| `ProjectionWorkTest` | Work counts that do not depend on the runner, at two document sizes: text reads through a counting snapshot for a layout, a viewport, a fold and an edit among collapsed regions, and lines visited and measured per edit through the package-private `linesVisited` and `linesMeasured` |
| `FoldingTest` | The projection against a display text built with the collapsed lines left out, through 80 seeded rounds of random region sets, folds, unfolds, reveals and edits that join and part CR LF pairs, wrap on and off, replayed on a mirror (`-DfoldRounds=N` runs more); rewraps with folds, the expand rules, the offered regions' reconciliation, indentation regions, a worker-measured Fold All applied, stale after an edit or a rewrap and dropped after a fold |
| `FoldingCommandsTest` | The fold commands, caret moves over a collapsed region, the reveal, Next and Previous Bookmark onto a hidden line, an edit at a hidden secondary caret, independent split views, smart indent through the controllers |
| `ColumnMetricsTest` | Column 150 at a 7.22 px advance |

### Syntax highlighting

| Class | Covers |
|---|---|
| `SyntaxHighlighterTest` | The highlighter on real workers: view lines arrive and the regions follow the pass, an edit's shifted tokens paint at once and the final tokens equal a new pass, outputs queued for an older revision do not overwrite newer text |
| `SyntaxControllerTest` | Detection by name and first line, detection again after Save As under another name, the manual choice across a reload, the Syntax menu's five recent grammars, a user grammar replacing a built-in, the notice for an invalid file, both comment commands at two carets as one undo step |
| `SyntaxMenuTest` | The category submenus of the built-in grammars, a user grammar in its category or in User, the recent list |
| `GrammarWiringTest` | Fold regions from the highlighter for a `brackets` grammar and from indentation for the others through switches and a reload; the grammar's indent rules and pairs; bracket matching that counts a string's bracket until the tokens arrive and skips it after |
| `HighlightComposerTest`, `TokenCacheTest`, `GrammarRulesTest` | The background precedence; the cache through edits; the indent regexes and the token filter |

### Search and replace

| Class | Covers |
|---|---|
| `FindControllerTest` | Incremental find and highlights, wrap notices, the selection seed, a result dropped after an edit, regex and replacement failures, Replace and Replace All as one undo step each, the notice for a Replace All dropped after an edit, Esc dropping extra carets before it closes the bar, a regex replacement with the line ending, a read-only document, highlights in both views and their shift on an edit; the saved history (what saves an entry, the 20-entry and 1,000-char limits, a refused Replace saving nothing, the text list shared with HyperSearch) |
| `SearchControllerTest` | Every scope; a dirty buffer searched as edited inside a tree search; the dock's rows; opening a match in an open document and in a closed file; an edit moving a match and one marking it stale; a file changed on disk; Next and Previous Result wrapping; a stopped rerun keeping the finished set and a done one replacing it; the retained limit with a pin; the dialog's seed and history; the refused requests; the workspace folder; a tree searched through a symbolic link reading the open buffer once |
| `HyperSearchTest` | Documents and a folder in one set, the match limit, a refused reservation, a cancel, a regex overflow on its group, a document group following edits |
| `HyperSearchWorkTest` | The memory charge of a set, a long line charged by its snippets, the memory and match limits, the UI posts per search |
| `ReplaceControllerTest` | A selection replaced as one undo step; open documents with one changed since the search skipped; a tree replace with an unchecked match and file; a CRLF file; a file changed between the preview and Apply; a read-only file; a file opened after the search edited as its buffer; Restore Run refusing a file changed since and restoring the others; a restore reloading a clean open document; Stop between 300 files leaving the rest untouched; Rerun File on a stale group; Save As onto a dirty file open in another tab |

### Workspace and navigation

| Class | Covers |
|---|---|
| `WorkspaceControllerTest` | A directory listed when it expands, Refresh keeping the old rows until a new file arrives, 1,200 entries shown 500 at a time, Reveal Active File's chain and a file outside the folder, the index's charge across a folder change and its return on dispose, Open in Default Application by extension and by the executable bit (POSIX only) |
| `QuickOpenTest` | `:line` and `:line:column` parsing, name matches before path matches and shorter paths first, an open document listed once and first, a file opened at a line and column, Switch Document, an index over 2,000 files matched on a worker |
| `GoToSymbolTest` | `@` and `file@symbol` parsing, Go to Symbol's rows, filter and jump with its history entry, a scan of an older revision dropped and run again, plain text and a grammar without rules, `file@symbol` opening at the best match and a notice without one |
| `NavigationHistoryTest` | Go to Line and Next Bookmark positions following an insert, a jump after Go Back dropping the positions ahead, plain caret moves recording nothing, a tab switch recorded once, a closed file reopened at its line with an untitled document skipped, a deleted file dropped with a notice, the 100-position limit |
| `RecentFilesTest` | Newest first, a reopen moving a file up, a file opened through a link and its target as one entry, the 20-file limit, Clear |
| `WorkspaceFilesTest` | Search in Folder, New File, New Folder, Rename and Delete through the controller: refused names, no Rename or Delete on the root, a case-only rename of an open document, a folder rename moving open dirty documents, closed-file state and the recovery file, Save and Reload (prompt of a dirty document, read of a clean one) refused while a rename runs, a rename reading the new name's per-file settings, a save refused during a rename running no cleanup, a folder delete with its counts leaving a dirty document missing, a file delete leaving the index, a link to an outside folder deleted alone |

### Other classes

`CommandLineParserTest`, `SettingsLocationTest`, `WindowGeometryTest`,
`ThemeControllerTest`, `PaletteTest`, `ThemeColorsTest`, `FontFilesTest`
(generated sfnt files), `UiTextTest`, `RecentLogTest` and
`EventLoopWatchdogTest` cover the rest.
