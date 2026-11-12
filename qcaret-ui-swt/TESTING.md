# qcaret-ui-swt testing

## Harness

Tests tagged `swt` open real windows; the root `README.md` lists where they
run.

- Every test class runs under `HangWatchdog`, registered through
  `META-INF/services`: a test still running after 120 s
  (`qcaret.test.hangSeconds`) prints every thread's stack and halts the test
  JVM 5 s later, so a native call that blocks the main thread ends the CI job
  with a stack.
- `EventLoop.runUntil` dispatches events until a condition holds, with no fixed
  sleep.
- No test opens a popup under a pump: a win32 popup would block.
- A pixel check skips when `Control.print` returns false.
- A test that reads screen pixels calls `ScreenProbe.assumeShows` first: a
  probe child of a known color must read back through `GC.copyArea`, or the
  test aborts with the platform in the reason. A covering window (the
  `windows-11-arm` runner's first-run screen) and GTK 4 fail the read. A print
  read checks a known color in the image with `ScreenProbe.assumeDrawn`.
  `ScreenProbeTest` opens a covering shell on GTK 3 only: on win32 the probe
  reads through a covering shell. Screenshot-only copies are
  unguarded.
- A test that posts a press through `notifyListeners` and dispatches events
  before the release drops real `MouseMove` events in a Display filter added
  before the window opens, so it runs before `EditorGestures`' filter, as
  `EditingSurfaceTest` does: a real pointer over the window sends motion
  without `BUTTON1`, which ends the posted drag. A press and release posted
  with no dispatch between them needs no filter.
- `-Dqcaret.screenshots=<dir>` makes the surface tests write PNGs of their
  windows; `-Dqcaret.shots=<dir>` does the same for `TabMoveGuiTest`.

### GTK theme

Tests take the theme from `XDG_CONFIG_HOME`, set in the module POM to
`src/test/resources/gtk-config/adwaita`, so the dark switch runs on every Linux
host. `LightOnlyThemeTest` runs in a second surefire execution against a
missing theme and skips in the first.

### GTK 4

Under GTK 4 the window cannot grow past the screen, `Control.print` draws
nothing (SWT issue 2819) and `GC.copyArea` from the Display reads white, so the
checks that need them skip there. `EditingSurfaceTest` paints the text into an
image with a Paint event over the client area where `Control.print` fails.

## Coverage

### Main window, menus and toolbar

| Class | Covers |
|---|---|
| `MainWindowTest` | The requested client size and position under X11, clamping to the work area, close requests, the dispatcher after disposal |
| `ShellWindowTest` | Opening and closing documents enabling and disabling Save, Paste and Next Tab with no menu shown; the Other encoding cascades filled once; one tab menu reused across tabs and after a drag, with the dragged document still shown; a font change that keeps the `StyledText`, the menu bar and the focus; the status fields; the warning marker and the log window; the busy count; the binary prompt, its marked tab and status, Save disabled and a typed key refused; column 150, where the widget's x must match `ColumnMetrics` within 1 px and a secondary caret's pixels must sit at the widget's position |
| `ToolbarAndMenusTest` | The toolbar's order, icons and tool tips, its buttons against `CommandStates`, View > Toolbar with the choice in the settings file, a live theme switch's ink and faded buttons, a narrow window; posted Ctrl+X, Ctrl+V, Ctrl+C and Ctrl+Z in the find field with the items disabled and Ctrl+C in a read-only view, on GTK 3; the editor menu's selection rule, keyboard request, commands, split-view target and key change; the tab menu's side closes; View > Line Numbers in existing and later views; the menu items' icons with a theme switch both ways |
| `MenuIconsTest` | The command-to-icon table, both inks at both sizes, the shared root element, `MenuIcons` per platform |
| `TabMoveGuiTest` | The menu items and tab drags over real shells, with a `PointerProbe` naming the control under the release |
| `DocumentBehaviorSurfaceTest` | The drop target's one transfer, `dropped` over an open editor and with a name that does not parse, the load row with Esc and Cancel, the title, Mod+drag and the small move of a Mod+click, the docks' weights, Recently Closed, Automatic Detection, the tab width and reload prompts, `AppMenu.open`'s guards. A real drag is not simulated |
| `SwtUiDispatcherTest` | A canceled timer does not run; cancel after dispose does nothing |
| `ChecksumSurfaceTest` | Tools before Window with four enabled cascades of three items; Generate hashing as typed and Esc; Verify Checksums over the open list; with `qcaret.rm.out`, both dialogs in light and dark |

### Editor

| Class | Covers |
|---|---|
| `EditorSurfaceTest` | Typing, Enter, undo and redo by key events, an edit the widget makes itself (`insert`), and Save, all checked in the model and the file; word wrap on a generated 200,000-line file with no gap over 1 s between 10 ms timer ticks on the UI thread; the unsaved prompt's Cancel and Don't Save, from Close and from Exit; a dragged tab that keeps its document shown; the double click past a wrapped row's edge; a pixel check of a secondary caret after CJK text at its measured position and of gutter ink |
| `EditingSurfaceTest` | Add Caret Below, every caret moved and extended by key, a caret added with Mod+click and dropped with Esc; an Alt drag from column 150 across long and short lines, scrolling the widget between press and drag, with the block, its copy, and in a print of the widget the block's left edge on a short line within 1 px of the measured column 150 on a long line, the virtual part painted after a resize and a horizontal scroll; composition text through the content kept out of undo; `IME` events for a composition over a selection and past column 0 with the commit's key events (a cancel keeps the selection, a commit replaces it as one undo step, the `IME`'s offset stays on the composition); Go to Line through its dialog; Keyboard Shortcuts filtered and copied; a block drag held below, right, left and above the view scrolling until release, focus loss and tab close; the primary caret's image color and size and, polled while the caret blinks, its screen pixel in both themes. Every mouse event is posted; a filter drops real pointer motion, which on GTK 4 reached the text from a pointer an earlier test left there and ended a posted drag |
| `SplitAndFindTest` | Typing in one of two views with the text and both carets checked, the arrangement switch, unsplit; the find bar's selected match, the matches in both views, the match background in pixels in the light and the dark theme, Escape closing the bar and clearing the matches, Esc in the editor dropping an extra caret before it closes the bar; the Focus Other View item showing F6 and switching views |
| `SyntaxPaneTest` | A Java file's keyword in its palette color and bold, a number keeping its color under a find match, Java checked in the Programming Languages A-M cascade, Python chosen from N-Z listed first on the next show, a switch to Plain Text clearing the styles and the status field |
| `FoldingSurfaceTest` | Fold marker clicks with the widget's lines against the projection's rows, a caret moved by key over a collapsed region, one split view folded and typed in with the other unchanged, Enter after an opener |
| `ProductivitySurfaceTest` | A command run from the palette's filter, a word completed from the editor's keys, the bracket and occurrence backgrounds and a bookmark bar left of the fold marker column in pixels, the marks toggled |
| `FontFamilyTest` | Installed families from the scan: bold keyword rows keeping every column in two families, a family change measuring the gutter again at a size where two families' number columns round apart, a missing or proportional family falling back, the family and the zoom keeping each other, an open log window following, a family the toolkit lacks not offered, the Preferences dialog listing, applying and restoring a theme file in Editor colors and a family on the Appearance tab. Its two-family cases skip on a host with fewer, and the gutter case on a host where no size separates them |
| `EditorLayoutGridTest` | A row forced off the grid to reach the measured path; every family measured on the Linux host has equal regular and bold advances |
| `CaretInkTest` | The caret image color under each operator for both palettes |

### Docks and dialogs

| Class | Covers |
|---|---|
| `SearchSurfaceTest` | Two tree searches from the dialog into the dock with the rows newest first, a group expanded, a match opened with Enter and F4 to another file; the strip's find marks, a click on the strip scrolling there, the slider following that, its own moves and the wheel, hiding and showing the strip, its recolor on a theme switch and a strip in each split view, the slider following Page Down and Fold All; both find bar fields listing the saved history, the field's width with a 1,000-char entry, HyperSearch listing the same texts |
| `ResultRowsTest` | Row paths through expansion, expansion kept by set id, groups collapsed when a search ends |
| `ReplaceSurfaceTest` | The preview opened from the dock over a tree search, a typed replacement, an unchecked match, Apply, the report's rows and the files, the run restored from the report |
| `BookmarksSurfaceTest` | A plain bookmark's bar and a commented bookmark's flag in pixels, by color and by x range, in both themes; the tooltip over the gutter and its absence elsewhere; the comment prompt; the list's cells, Go To, Edit Comment, Remove and Hide; both docks shown at once |
| `WorkspaceSurfaceTest` | Browser rows expanded, a file opened with Enter, a path copied, the active file revealed, a Quick Open match chosen at `:2`, the Recent Files entry, an empty Quick Open (the open document in bold, then a closed recent file) |
| `WorkspaceDockLayoutTest` | At the default window size and side weights: two directories expanded by clicks on their expanders, posted with `Display.post` on GTK 3 and through XTest from `python3` on GTK 4 (which has no `Display.post`; skips without it), each listing 1,000 entries so the loading row shows first; every button, field and the Hidden check box inside the dock and no button narrower than its preferred width; the three columns inside the tree and Size and Modified wider than each cell text; a row's tool tip for a file and a folder, unchanged within a row and cleared on exit; the drag source's Copy-only `FileTransfer`, its data for a folder (none) and a file, and that data opening through `MainWindow.dropped`; a file dragged from the tree onto the editor and onto the tab strip by an XTest gesture from a `python3` process on GTK 3 and GTK 4, since SWT's drag detection runs on the UI thread and needs the pointer to move meanwhile (skips without `python3` and libXtst; GTK only) |
| `WorkspaceInkTest` | GTK only. Starts dark and checks each browser button's icon ink against the toolbar's, after a switch to light and back, and after hiding and showing the browser; skips when the toolbar never draws dark (a GTK theme without a dark variant). Regression: the button row draws no background, so its GTK print read white and the buttons kept the dark ink on a dark window |
| `GoToSymbolSurfaceTest` | Go to Symbol's field starting with `@`, the rows and the detail column heading, a filtered row chosen with Enter moving the caret |
| `CompareSurfaceTest` | A dirty document's file changed and the window activated until the prompt shows; Compare from it with both sides' rows, Next, Previous and Shift+F7 with wrapping, the other side following a scroll, the dark palette's background after a theme switch; Reload from the prompt loading the file |
| `PreferencesSurfaceTest` | A refused field keeping the dialog open on its tab, Restore Defaults applying nothing, the Keys tab refusing a typing or taken key and a free one reaching the menu item's accelerator, Clear History's prompt declining and clearing with an open find bar following, a clear the file refuses (a directory at `qcaret.conf`) showing the notice and keeping the other settings, and a retry clearing the file, a theme switch leaving it working, a new size remembered |
| `FilePollSurfaceTest` | `file.pollSeconds` applied in the dialog with the dialog left open: a change written through a hard link in another directory, which no directory watch on Linux reports, reloads the clean buffer |
| `RecoverySurfaceTest` | The recovery dialog and a restored split on a real window, with the top line of a tab shown later and a fold in one view |
| `GuideWindowTest` | User Guide to its window or fallback, GTK 4's notice, the locations `allowed` takes, a result arriving after the window closed, a throwing browser factory, `LoadBound`. A real browser's outcome is never raced against a short timer: cocoa and GTK resolve that race opposite ways |
| `PrintOutputTest` | A print through a real window and `Printer` to a PDF, read back with PDFBox: page count, header, every line, wrap, a range, a selection, copies, Cancel, and the Light token colors under the Nightwater scheme, read from the PDF's fill colors. The dialog's answer comes through `MainWindow.setPrinterChooser`; it uses only GTK's Print to File or a Windows printer named PDF, never a physical printer, and skips when neither exists |

### Theme

| Class | Covers |
|---|---|
| `ThemeAndDialogsTest` | The theme switched from the View menu, tool icons re-inked on a dark background, `showsNow` per platform, the About logo by background, a text window over a modal dialog, Licenses over About, the menu bar without an application menu; `CocoaChrome` on any platform (control colors following an appearance change, palette-colored controls keeping theirs, a control created during a change colored, `refresh` reporting one change once) |
| `TabColorsGuiTest` | The tab strip's screen pixels through `GC.copyArea` in light, dark, light and dark, light, dark order, with focus outside and inside the folder: contrast of at least 4.5 and a strip of the theme's lightness. On GTK the status bar and its Warnings link take the same check and the About logo must match the theme. When `ScreenProbe` finds the window covered, win32 reads through `Control.print` and other platforms skip. Skips on GTK 4 |
| `LightOnlyThemeTest` | On a missing GTK theme: the dark palette with light chrome, dark ink and the light logo |

### Resources

| Class | Covers |
|---|---|
| `ResourceLeakTest` | `DeviceData.tracking` over two warm-up rounds (the second adds one cached `StyledText` line layout) and four rounds of: tab open and close, the tab menu, the editor menu, the toolbar, the line numbers hidden and shown, both themes, zoom, Preferences through a theme switch, split with Fold All and Unfold All in both views and the find bar searching and closed, unsplit, the command palette, the completion list with the whitespace and line-ending marks on, a HyperSearch from its dialog into the dock with a group expanded, the replace preview opened over it and closed, the overview strip hidden and shown, a bookmark comment through its prompt with the gutter tooltip and the Bookmarks list opened and hidden, the workspace browser with a directory expanded and its row menu, Quick Open filtered and closed, a comparison with the file on disk opened, recolored by both themes and closed, the change prompt and the document chooser opened and closed, and every menu's `Show`. The live counts per type must return to the warm-ups', and the theme and zoom steps must replace the primary caret image. System cursors are left out: GTK SWT moves its cached cursors to a list it disposes with the Display on each theme switch, so the next request allocates another |

### Screenshots

`GuideScreenshotsTest` runs only with `-Dqcaret.guide.shots=<dir>`
(`tools/screenshots.sh`) and writes the user guide's images from generated mock
files; `EditorSchemeShotsTest` writes `editor-colors.png` there, and with
`-Dqcaret.scheme.shots=<dir>` one PNG per editor color scheme of a Java,
Markdown, XML and shell file. With `-Dqcaret.screenshots=<dir>` these tests
also save PNGs:

| Class | Files |
|---|---|
| `EditingSurfaceTest` | The multi-caret, block and dark-caret windows |
| `FoldingSurfaceTest` | `folding.png` |
| `ProductivitySurfaceTest` | The marks with a bookmark and occurrences, the open palette, a Java file with a collapsed region, occurrences and a matched bracket (`merge.png`) |
| `BookmarksSurfaceTest` | `bookmark-tooltip.png` (the whole screen, so the tooltip shows), `bookmarks-list.png` |
| `WorkspaceSurfaceTest` | `workspace-browser.png`, `quick-open-empty.png`, `quick-open.png` |
| `GoToSymbolSurfaceTest` | `go-to-symbol-light.png`, `go-to-symbol-dark.png` |
| `CompareSurfaceTest` | `change-prompt.png`, `diff-view.png` (the whole screen) |
| `ReplaceSurfaceTest` | `replace-preview.png`, `replace-report.png` |
| `RecoverySurfaceTest`, `PreferencesSurfaceTest` | The recovery dialog and the restored split; each Preferences group |

## IBus test

`IBusCompositionTest` (tag `swt`) drives a real input method through the GTK 3
path. `IBusSession` starts `Xvfb`, `ibus-daemon` under `dbus-run-session` and
the `IBusDriver` JVM, each with an environment built from nothing and a private
`HOME`, `XDG_*` and display, so key events cannot reach a desktop session. GLib
double-forks what the daemon spawns; `close` finds those processes by a mark in
`/proc/<pid>/environ`. `IBusDriver` opens a real `MainWindow`, sends X key
events with `xdotool key` (XTEST) and writes
`target/diagnostics/ibus/results.txt` with each check's result and its
`ImeComposition` and `KeyDown` trace, next to each process's log.

The engine is `table:cangjie5`: a table with no language model, in the
`ibus-table-cangjie5` package. The checks name its characters, and a host whose
table composes `a` differently counts as unavailable.

| Host | Default | `-Dqcaret.ibus.required=true` |
|---|---|---|
| Windows, macOS | Skips | Skips |
| GTK 4 (`SWT_GTK4=1`) | Skips | Fails |
| Linux without `Xvfb`, `dbus-run-session`, `ibus-daemon`, `ibus` or `xdotool` | Skips | Fails |
| Linux where the daemon, the engine or the GTK module does not work, or the first key composes nothing | Skips | Fails |
| Linux with all of them | Runs | Runs |

The `test-ibus` CI job installs the packages and sets the property. To run it
elsewhere, install `dbus ibus ibus-gtk3 ibus-table ibus-table-cangjie5 xdotool`
and run:

```sh
xvfb-run -a ./mvnw -B verify -pl qcaret-ui-swt -am \
  -Dtest=IBusCompositionTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dqcaret.ibus.required=true
```

Events on GTK 3 under IBus 1.5.29, which `eventsMatchEditingSurfaceTest` pins
and the `EditingSurfaceTest` helpers send:

| Step | `ImeComposition` and key events |
|---|---|
| First composition text | `COMPOSITION_SELECTION`, then `COMPOSITION_CHANGED` over the selection |
| Update | `COMPOSITION_CHANGED` over the composition |
| Cancel | One `COMPOSITION_CHANGED` over the composition with empty text; no `COMPOSITION_SELECTION` |
| Commit | That same empty change, then `COMPOSITION_CHANGED` with an empty range, the text and `getCommitCount` equal to its length, then one `KeyDown` per UTF-16 unit with `keyCode` 0 |

The engine commits a composition when the widget loses the focus, and keeps it
through a reload or save that cleared the model's copy, committing at the next
key.
