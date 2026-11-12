# Theme and icons

`SwtThemeService`, `CocoaChrome`, `DialogSurfaces`, tab colors, `ToolIcons` and `MenuIcons` in
`ui.swt` of [qcaret-ui-swt](../README.md). The palette and theme modes are in
[qcaret-app help and themes](../../qcaret-app/docs/help-and-themes.md#themes).

## Native controls

`SwtThemeService` reports `Display.isSystemDarkTheme` with a change event from
`SWT.Settings`, polled every second on cocoa, and sets native controls' theme:

| Platform | Native controls | A switch at runtime |
|---|---|---|
| GTK | `setDarkThemePreferred` before the first Shell for light and dark, and at every switch | Applies at once. The system setting is read once at Display creation, so a desktop change applies at the next start |
| win32 | `setDarkThemePreferred` before the first Shell; controls read it only when created | Native controls change at the next start; `showsNow` is false and the controller shows a notice. Under a dark start only the title bar, menu bar, push buttons and read-only combos draw dark, and `DialogSurfaces` colors dialogs; see [win32](#win32) |
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

Under a dark startup setting win32 draws check box labels near-white while the
main window's docks and bars stay light, so every check box comes from
`SwtThemeService.check`, which creates it with the theme off outside a dialog
`DialogSurfaces` colored. A `MenuItem` or `Table` with `SWT.CHECK` has no such
label and does not use it.

Measured on `windows-2025` with SWT 3.135.0 under a dark startup setting
(`Win32DarkInkTest` saves the window and the readings to
`target/diagnostics/win32-dark/`):

| Drawn dark | Drawn light |
|---|---|
| Title bar, menu bar (`#303030`), push button faces (`#333333`) with white labels, read-only `Combo` | `ToolBar`, `Composite`, `Label`, `Link`, `Group`, check box and radio button panels, `Text` and a drop-down `Combo`'s field (white), `List`, `Table`, `Tree`, `Spinner`, `ProgressBar` |

`getBackground` reports the light window color for a push button as well, so an
icon inked from it is dark on dark. Icons therefore sit only on tool bars,
which draw light like the window; the main toolbar and the workspace browser's
row read `#f0f0f0`. `Text.setBackground` and `setForeground` do color a field
under the dark setting, its hint and a disabled field's background included
(disabled text stays system gray), but QCaret keeps
the main window's fields native: the dock, tree and toolbar around them stay
light.

#### Dialog colors

`DialogSurfaces.apply` runs on every dialog shell: `Dialogs.shell` calls it
beside `inheritIcons`, and `ArchitectureTest.ownedShellsTakeDialogColors` fails
on a `new Shell` elsewhere without it. The main window and the completion popup
keep native colors. GTK and cocoa (`NativeDialogSurfaces`) change nothing.

Under a dark startup setting `Win32DialogSurfaces` sets the colors below, forces
the body color onto children so push button corners match, and colors controls
created after the shell shows from `SWT.Skin`. A control with
`EditorPane.PALETTE_COLORED` keeps the palette. `Dialogs.background` returns the
body color, so About picks the dark logo.

| Control | Background | Text |
|---|---|---|
| Shell, `Composite`, `Label`, `Link`, `Group`, check box, radio button, `TabFolder` page | `#202020`, the title bar | White; link color raised to 4.5 contrast |
| `Text`, drop-down `Combo`, `List`, `Table` and its header, `Tree`, `Spinner` | `#2b2b2b` | White |
| Read-only `Combo` | `#333333`, the push button face | White |
| Push button | Native `#333333` face | Native white |
| `ProgressBar` | `#202020` | Native bar color |

A forced background makes a read-only combo draw its own text in the
foreground color, black by default, so it gets explicit colors. Measured on
`windows-2025` by `Win32DarkDialogsTest`, which saves each dialog to
`target/diagnostics/win32-dialogs/`; the `windows-11-arm` runner shows another
window over the desktop and skips it.

Public API leaves these light or gray:

- `TabFolder`'s tab strip and frame (Preferences): the native tab control draws
  them.
- `Table` grid lines and header dividers (the Keys page).
- `Spinner` arrow buttons.
- Disabled `Text`, `Label` and check box text, in system gray: `#6d6d6d` on a
  field (2.7 contrast), `#808080` on the body.
- The Guide's page, which its HTML colors.

The colors follow the startup setting, as native controls do: after a live
switch an open dialog and a newly opened one keep them until the next start.

### cocoa

Cocoa SWT sends no `SWT.Settings` for an appearance change and keeps its
startup colors (measured on macOS 15 in QLogg). While the appearance differs
from the startup one, `CocoaChrome` sets each shell's, table's, tree's, list's
and field's colors to the current appearance, at the change, at `SWT.Show` of a
shell and at `SWT.Skin` of a new control, since the docks replace their tables
while open. It follows the system appearance, not `ui.theme`.

- A control with `EditorPane.PALETTE_COLORED` keeps its colors: the editor,
  compare and replace preview `StyledText` and the tab strip, which the palette
  owns and nothing would restore.
- `ReplaceDialog` marks its computing note with a style range, because
  `setForeground(null)` returns a field to the startup color.

## Palette application

`MainWindow` applies a palette to the `StyledText` colors, the editors' painted
marks (margin, indent guides, modified-line marks) and the tab strip, then
re-inks its `ToolIcons` on the next UI turn, after native controls have
restyled.

`ui.editorColors` can put the palette on the other side from native controls,
so every surface takes its colors from one of the two, never a mix:

| Colors from | Surfaces |
|---|---|
| `Palette` | Editor text, gutter, caret, overview strip, tab strip, compare window views, replace preview |
| Native theme | Menus, toolbar and its icons, docks, find bar, status bar, dialogs, title bars, About logo |

The replace preview takes the palette's text and background, because its
removed and added tints derive from the palette background: under native colors
the other side's text lands on them. The one palette color on a native
background is the Search Results match mark, `findMatch` with `text` over it, a
pair `PaletteTest` holds at 4.5.

`ToolIcons` picks dark or light ink from the owner control's background
luminance, not from the palette, and `reink` swaps every applied item's image
when that changes. It also listens to `SWT.Settings`, from the Display or from
`CocoaChrome` on the owner. On GTK `getBackground` is stale until that event,
and `MainWindow`'s `reink` runs on the turn before it, so `reink` reads the
background through `PlatformTraits.shownBackground`, which on GTK prints the
visible owner and reads a pixel; a hidden toolbar re-inks when it
shows. GTK's disabled effect brightens an icon on a dark background, so there
`showState` gives a disabled item a copy at 40% opacity.

The tab strip's buttons (`TabStripButtons`) sit on the palette's `gutter`, not a
native background, so their `ToolIcons(owner, palette)` takes the palette's
ink, and `MainWindow.applyPalette` changes it through `setPalette`, which calls
`follow(palette)`.

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
editor menu, through a `ToolIcons` of the window's own. `ToolIcons.item`
makes the workspace browser's icons, each a push item alone on a flat
`ToolBar`, inked from the main toolbar's background.

Every tool and menu icon comes from `ToolIcons`:
`ArchitectureTest.iconsOnlyThroughToolIcons` fails on a `setImage` call on a
tool item, menu item, button, table or tree item or `CTabItem` anywhere else. A
set takes its ink from one of two sources:

| Source | Constructor | Ink changes |
|---|---|---|
| Native background of a tool bar or menu | `ToolIcons(owner)`, `ToolIcons(owner, source)`; win32 menus `ToolIcons(owner, nativeDark)` | `reink`, on `SWT.Settings` and after a palette change |
| Palette, for a surface QCaret paints | `ToolIcons(owner, palette)` | `follow(palette)` |

On GTK 4, `ToolIcons.apply` turns off shrinking on a tool item's GtkPicture
through SWT internals (`ToolItem.imageHandle`, `GTK4.gtk_picture_set_can_shrink`).
SWT 3.135.0 wraps that picture in a box that measures 0 high for any width under
GTK 4.14, so the tool bar lays an image-only item out at its padding, 12 px, and
shows no icon. If an SWT upgrade renames either internal, the items fall back to
that layout and `ToolbarAndMenusTest.everyButtonIsWideEnoughForItsIcon` fails in
the GTK 4 lane.

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
