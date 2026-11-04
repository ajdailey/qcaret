# qcaret-ui-swt

The SWT views: the main window with its tabs and menus, the editor
(`ui.swt.editor`), dialogs, the theme service and the UI dispatcher. It
depends on `qcaret-app`, `qcaret-core` and the build host's SWT fragment, and
uses public SWT API only. The package `ui.swt.search` is empty.

## Main window

`MainWindow` holds a `CTabFolder` with one tab per document, each an
`EditorPane`, and the File, Edit, View and Help menus built from `MenuTable`.
`attach` connects the controller: menu items run
`ApplicationController.execute`, a window-system close request runs Exit, and
the check items (Word Wrap, the themes) follow `ApplicationController.checked`.
A window-wide command's accelerator (`Mod+S`) becomes a menu accelerator with
Mod as Ctrl, or Command on macOS.

- A tab's close button and a middle click ask the `DocumentController` to
  close it. Dragging a tab within the strip moves it: an item cannot change
  position, so the move disposes and recreates it, and the `Selection` that
  `CTabFolder` sends for a neighbor while the selected item is disposed is
  ignored, so the moved tab stays shown.
- Open and Save As use the native `FileDialog`, which runs a nested event
  loop. The unsaved-changes prompt (`UnsavedDialog`) does not: Save, Don't
  Save and Cancel answer through a callback, and closing it answers Cancel.

- `place` sizes and positions the shell through `WindowGeometry` before
  `open`. On Wayland (`org.eclipse.swt.internal.gdk.backend` is `wayland`) the
  position is skipped and the size applies. On win32 `computeTrim` overstates
  the height once a menu bar is set, so the window is placed again with the
  trim measured from the sized shell.
- Every unmaximized move and resize records the normal bounds, which
  `windowBounds` returns for the quit to save.
- The window icon is the `qcaret-*.png` set at 16 to 256 px
  (`Icons.Variant.DARK`).

Dialogs (`Dialogs`, `ErrorDialog`, `AboutDialog`, `TextWindow`) open without a
nested event loop: `open` returns at once and buttons act through listeners.
`showText` opens its window modal and owned by the innermost visible modal
dialog when one is open, because GTK gives input only to the newest modal
shell; Licenses opened from About therefore stays usable. Three calls do run a
nested event loop, so code around them must tolerate re-entrant events:
`FileDialog.open`, `ColorDialog.open`, and `Clipboard.getContents` on GTK,
which `MainWindow.clipboardText` uses.

## Editor (`ui.swt.editor`)

`EditorPane` is one editor: a `StyledText` over a `ContentAdapter`, the
`StyledTextContent` that serves the display rows of the document's
`DisplayProjection`. The model is the only authority:

- The adapter turns each projection change into one `textChanging` and
  `textChanged` pair, so the widget never re-reads the whole text after an
  edit. It sends no `textSet`; a loaded document gets a new adapter, read in
  `setContent`. A `replaceTextRange` or `setText` from the widget (IME preedit
  text) goes to the controller as an edit.
- A `VerifyKey` listener turns typing, Enter, Tab, Backspace, Delete, Esc,
  Mod+Z, Mod+Shift+Z, Mod+Y, Mod+X, Mod+C, Mod+V, Mod+A and the Insert-key
  clipboard keys into `EditorInput` calls and consumes them; Ctrl+Alt (AltGr)
  and, on macOS, Option produce typed chars. Any edit the widget still makes
  (word delete, middle-click paste, drag and drop) arrives as `Verify`, is
  canceled, and goes to `EditorInput.replaceFromView`; the controller applies
  it inside the listener, before `modifyContent` returns.
- Navigation keys and the mouse move the widget's caret natively, and each
  move goes to the model. The widget holds only the model's primary
  selection, set again after every model change and every display change;
  caret moves the widget makes while it applies a change are ignored. An
  Alt+click that adds a widget selection goes to the model as a second
  selection.
- The gutter is painted in the widget's left margin by a paint listener, with
  the line number on each line's first row; `ColumnMetrics` places the digits
  and sizes the margin. The current line's rows get the palette's
  current-line background (`LineBackgroundListener`). Secondary selections are
  line style backgrounds and secondary carets are 2 px bars at
  `getLocationAtOffset`, so both follow the widget's measured glyphs on any
  script.
- Every resize sends the columns that fit the text area for word wrap. A
  double click at or past a wrapped row's right edge selects the row's last
  word, where the widget alone would take the trailing space and the virtual
  break.
- Font, tabs, margins and the text and background colors each make the
  widget reset its per-line cache, O(rows) through a `TreeSet` (about 1 s at
  1M rows): the pane sets them before `setContent`, and a palette change sets
  only colors that differ.

## Themes

`SwtThemeService` reports `Display.isSystemDarkTheme` with a change event from
`SWT.Settings`, and sets native controls' theme:

| Platform | Native controls | A switch at runtime |
|---|---|---|
| GTK | `setDarkThemePreferred` before the first Shell for light and dark, and at every switch | Applies at once. The system setting is read once at Display creation, so a desktop change applies at the next start |
| win32 | `setDarkThemePreferred` before the first Shell; controls read it only when created | Native controls change at the next start; `showsNow` is false and the controller shows a notice. Buttons, combo lists and checkboxes stay light |
| cocoa | Never set: always the system appearance | Only the text view recolors |

DO NOT PIN THE COCOA APPEARANCE, live or at startup. Cocoa SWT reads its
widget colors once, under the system appearance; an application appearance
that differs draws text in its own appearance over those backgrounds, white on
light gray or black on dark gray. Both a live `setDarkThemePreferred` and a
startup Display replacement failed this way.

`MainWindow` applies a palette to the `StyledText` colors, then re-inks its
`ToolIcons` on the next UI turn, after native controls have restyled.
`ToolIcons` picks dark or light ink from the owner control's background
luminance, not from the palette, and `reink` swaps every applied item's image
when that changes; it also listens to `SWT.Settings`. About picks its logo the
same way from the dialog background (`MainWindow.logoVariant`): native chrome
can differ from the palette on cocoa and on win32 before a restart.

## macOS application menu

`AppMenu.hook` wires the system's application menu on cocoa: About and Quit
run `ABOUT` and `QUIT` in the controller and are left out of the menu bar;
Preferences is disabled until a settings dialog exists; Hide, Hide Others and
Show All stay native. A system quit request (Cmd-Q, logout) arrives as a
Display `SWT.Close`, which is vetoed and runs `QUIT`, so the unsaved-changes
prompts and the ordered quit always run (unverified on cocoa). Elsewhere there is no application menu and `hook` does nothing.

## Icons

The application icon PNGs under `src/main/resources/.../icons/` are rendered
from `qcaret-dist/src/icons/*.svg` by `tools/icons.sh` and committed. The tool
icons are SVG in `src/icons/` (`currentColor` strokes); `src/build/IconRenderer`
renders each at 16 and 32 px in dark and light ink at `generate-resources`,
with jsvg, which is a build-only dependency.

## Tests

Every test class runs under `HangWatchdog`, registered through
`META-INF/services`: a test still running after 120 s
(`qcaret.test.hangSeconds`) prints every thread's stack and halts the test JVM
5 s later, so a native call that blocks the main thread ends the CI job with a
stack. `EventLoop.runUntil` dispatches events until a condition holds, with no
fixed sleep.

`MainWindowTest` (tag `swt`) checks the requested client size and position
under X11, clamping to the work area, close requests and the dispatcher after
disposal. `EditorSurfaceTest` (tag `swt`) drives a real window: typing, Enter,
undo and redo by key events, an edit the widget makes itself (`insert`), and
Save, all checked in the model and the file; word wrap on a generated
200,000-line file with no gap over 1 s between 10 ms timer ticks on the UI
thread; the unsaved prompt's Cancel and Don't Save, from Close and from Exit;
a dragged tab that keeps its document shown; the double click past a wrapped
row's edge; and a pixel check of a secondary caret after CJK text at its
measured position and of gutter ink. The pixel check skips when
`Control.print` returns false. `ThemeAndDialogsTest` (tag `swt`) switches the theme from the View
menu, re-inks tool icons on a dark background, checks `showsNow` per
platform, the About logo by background, a text window over a modal dialog,
Licenses over About, and the menu bar without an application menu.
