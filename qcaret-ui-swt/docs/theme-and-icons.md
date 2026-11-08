# Theme and icons

`SwtThemeService`, `CocoaChrome`, tab colors, `ToolIcons` and `MenuIcons` in
`ui.swt` of [qcaret-ui-swt](../README.md). The palette and theme modes are in
[qcaret-app help and themes](../../qcaret-app/docs/help-and-themes.md#themes).

## Native controls

`SwtThemeService` reports `Display.isSystemDarkTheme` with a change event from
`SWT.Settings`, polled every second on cocoa, and sets native controls' theme:

| Platform | Native controls | A switch at runtime |
|---|---|---|
| GTK | `setDarkThemePreferred` before the first Shell for light and dark, and at every switch | Applies at once. The system setting is read once at Display creation, so a desktop change applies at the next start |
| win32 | `setDarkThemePreferred` before the first Shell; controls read it only when created | Native controls change at the next start; `showsNow` is false and the controller shows a notice. Buttons, combo lists and checkboxes stay light |
| cocoa | Never set: always the system appearance | Only the text view and tab strip recolor; a system appearance change recolors the rest through `CocoaChrome` |

DO NOT PIN THE COCOA APPEARANCE, live or at startup. Cocoa SWT reads its widget
colors once, under the system appearance; an application appearance that
differs draws text in its own appearance over those backgrounds, white on light
gray or black on dark gray. Both a live `setDarkThemePreferred` and a startup
Display replacement failed this way.

### GTK

GTK SWT refreshes its system colors, repaints every shell and sends
`SWT.Settings` on the first `Display.sleep` after a theme change, so a loop
that never sleeps keeps the startup colors. `Gui`'s loop sleeps when idle.

A `Link` keeps the color of its creation through that refresh, so
`SwtThemeService` sets each link's color on `SWT.Settings`. `colorLink` also
moves that color toward white or black until it reaches 4.5 contrast on the
link's background: Adwaita's dark link color measures 3.25 on its own window
background. Every new `Link` calls it.

A GTK theme with no dark variant ignores `setDarkThemePreferred`. A named theme
that is not installed behaves the same way: GTK falls back to light Adwaita, as
on an Ubuntu system whose `/etc/gtk-3.0/settings.ini` names Yaru without Yaru
installed (a CI runner, a container). `SWT.Settings` still fires. Native
controls stay light under the dark palette, with dark ink and the light logo,
because both read the native background.

### win32

Under a dark startup setting win32 draws check box labels near-white while
dialog pages stay light, so every check box comes from `SwtThemeService.check`,
which creates it with the theme off. A `MenuItem` or `Table` with `SWT.CHECK`
has no such label and does not use it.

### cocoa

Cocoa SWT sends no `SWT.Settings` for an appearance change and keeps its
startup colors (measured on macOS 15 in QLogg). While the appearance differs
from the startup one, `CocoaChrome` sets each shell's, table's, tree's, list's
and field's colors to the current appearance, at the change, at `SWT.Show` of a
shell and at `SWT.Skin` of a new control, since the docks replace their tables
while open. It follows the system appearance, not `ui.theme`.

- A control with `EditorPane.PALETTE_COLORED` keeps its colors: the editor and
  compare `StyledText` and the tab strip, which the palette owns and nothing
  would restore.
- `ReplaceDialog` marks its computing note with a style range, because
  `setForeground(null)` returns a field to the startup color.

## Palette application

`MainWindow` applies a palette to the `StyledText` colors, the editors' painted
marks (margin, indent guides, modified-line marks) and the tab strip, then
re-inks its `ToolIcons` on the next UI turn, after native controls have
restyled.

`ToolIcons` picks dark or light ink from the owner control's background
luminance, not from the palette, and `reink` swaps every applied item's image
when that changes. It also listens to `SWT.Settings`, from the Display or from
`CocoaChrome` on the owner. On GTK `getBackground` is stale until that event,
and `MainWindow`'s `reink` runs on the turn before it, so `reink` reads a pixel
of the visible owner through `Control.print`; a hidden toolbar re-inks when it
shows. GTK's disabled effect brightens an icon on a dark background, so there
`showState` gives a disabled item a copy at 40% opacity.

About picks its logo the same way from the dialog background
(`MainWindow.logoVariant`): native chrome can differ from the palette on cocoa,
on win32 before a restart and on a GTK theme with no dark variant.

## Tab colors

`MainWindow.colorTabs` sets all four `CTabFolder` colors, because the folder
otherwise keeps the system list colors of its creation: a white selected tab in
the dark theme, and a white label on a Display created under a dark system
setting.

| Tab | Background | Label and close button |
|---|---|---|
| Selected, focus inside the folder | `selection` | `selectionText` |
| Selected, focus in a dock or another shell | `gutter` | `selectionText` |
| Unselected | `gutter` | `Palette.tabText` |

The 2 px bar on the selected tab is the `CTabFolder` default: the system's list
selection color, gray with focus outside the folder. SWT has no setter for its
color. A hovered close button is SWT's fixed red.

## Icons

The application icon PNGs under `src/main/resources/.../icons/` are rendered
from `qcaret-dist/src/icons/*.svg` by `tools/icons.sh` and committed. The tool
icons are SVG in `src/icons/` (`currentColor` strokes);
`src/build/IconRenderer` renders each at 16 and 32 px in dark and light ink at
`generate-resources`, with jsvg, which is a build-only dependency. Every icon
carries the same root element (16 px grid, 1.4 stroke, round caps and joins, no
fill) and no child overrides it; `MenuIconsTest` fails on a difference.

`MenuIcons.icon` maps a command to its icon, for the toolbar and the menus.
`MenuIcons.decorate` shows the icons on the push items of the menu bar and the
editor menu, through a `ToolIcons` of the window's own. `ToolIcons.button`
makes the workspace browser's icon push buttons, inked from the button row's
background like the toolbar.

| Platform | Item image | Ink | Items without an icon |
|---|---|---|---|
| GTK 3 | `MenuItem.setImage` | Same reading as the toolbar, live switch included | Hold `ToolIcons.blank` in a menu with an icon, because GTK 3 SWT reserves no image column and the label would move 22 px |
| GTK 4 | SWT ignores `setImage` | - | - |
| win32 | `MenuItem.setImage` | Fixed from `SwtThemeService.nativeDark`: menus take the startup setting, and the toolbar stays light under a dark one | Bare; the system reserves the column |
| cocoa | None; the application menu stays native | - | - |

A disabled GTK menu item shows SWT's disabled copy of its image, which keeps
the alpha and raises the ink's brightness. On a dark background
`ToolIcons.showState` gives it the faded image; `MainMenuBar` and `EditorMenu`
call it after changing an item. Check, radio, cascade and listing items show no
icon: GTK draws a check item's image beside the indicator, and on Windows 11
SWT draws it in the indicator's place. The tab menu is rebuilt on each show and
carries none.
