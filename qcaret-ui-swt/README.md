# qcaret-ui-swt

The SWT views: the main window, its menus and dialogs, the theme service and
the UI dispatcher. It depends on `qcaret-app`, `qcaret-core` and the build
host's SWT fragment, and uses public SWT API only. The packages
`ui.swt.editor` and `ui.swt.search` are empty.

## Main window

`MainWindow` holds one tab with an empty, read-only `StyledText` and the File,
View and Help menus built from `MenuTable`. `attach` connects the controller:
menu items run `ApplicationController.execute`, a window-system close request
runs `quit`, and the theme check items follow `ThemeController.mode`.

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
shell; Licenses opened from About therefore stays usable. Two calls do run a
nested event loop, so code around them must tolerate re-entrant events:
`ColorDialog.open`, and `Clipboard.getContents` on GTK, which
`MainWindow.clipboardText` uses.

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
Display `SWT.Close`, which is vetoed and runs `quit`, so the ordered quit
always runs. Elsewhere there is no application menu and `hook` does nothing.

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
disposal. `ThemeAndDialogsTest` (tag `swt`) switches the theme from the View
menu, re-inks tool icons on a dark background, checks `showsNow` per
platform, the About logo by background, a text window over a modal dialog,
Licenses over About, and the menu bar without an application menu.
