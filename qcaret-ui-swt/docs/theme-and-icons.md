# Theme and icons

`SwtThemeService`, tab colors, `ToolIcons` and `MenuIcons` in `ui.swt`, and
`ChromeColors`, `DialogColors`, `DialogTabs`, `ThemeFollow` and `CocoaChrome` in
`ui.swt.platform`, of [qcaret-ui-swt](../README.md). The palette and theme
modes are in [qcaret-app help and
themes](../../qcaret-app/docs/help-and-themes.md#themes).

## Native controls

`SwtThemeService` reports `Display.isSystemDarkTheme` with a change event from
`SWT.Settings`, and sets native controls' theme through `ThemeFollow`
(`ui.swt.platform`, one class per platform). On win32 and cocoa
(`ThemeFollow.watch`) it also reads the setting every 500 ms:
cocoa SWT sends no `SWT.Settings` for an appearance change, and win32 SWT sends
it 2 s after `WM_SETTINGCHANGE` (`Display.SETTINGS_DELAY`). The service reports
a change of the value only, so the poll and a later `SWT.Settings` apply it
once. Listeners on `SWT.Settings` itself, such as `ToolIcons.reink`, still run
at the event.

| Platform | Native controls | A switch at runtime |
|---|---|---|
| GTK | `setDarkThemePreferred` before the first Shell for light and dark, and at every switch; under System the GTK theme's own setting | Applies at once. The system setting is read once at Display creation, so a desktop change applies at the next start |
| win32 | `setDarkThemePreferred` before the first Shell, under System from `Display.isSystemDarkTheme` at start (`Win32ThemeFollow.start`); controls read it only when created | `Win32ThemeFollow.showOnWindows` sets it again and rebuilds every main window ([theme rebuild](main-window.md#theme-rebuild)). Under the dark setting only the title bar, menu bar, push buttons and read-only combos draw dark, and `DialogColors` colors dialogs; `ChromeColors` colors the main window's own surfaces at once; see [win32](#win32) |
| cocoa | `setDarkThemePreferred` pins the application appearance to the side `ui.theme` resolves to, before the first Shell and at each change, System included (`CocoaThemeFollow`) | Applies at once: `CocoaChrome` recolors every shell from the pinned side. Under System the pin follows a system change on the next 500 ms poll |

PIN THE COCOA APPEARANCE ONLY TOGETHER WITH `CocoaChrome`. Cocoa SWT reads its
widget colors once, under the system appearance at Display creation, and
`Display.runSettings` reads them again under that same appearance. A pinned
appearance that differs draws AppKit text in its own appearance over those
backgrounds, white on light gray or black on dark gray, unless `CocoaChrome`
forces the pinned side's colors. A pin alone, live or at startup, and a startup
Display replacement failed this way. SWT has no call that removes a pin, so
System pins the system side and re-pins at each change.

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

Popup menus (menu bar menus, cascades, context menus) keep the native side of
the start until QCaret restarts, after a View > Theme switch and after a
Windows mode change under System. The popup menu theme is process-wide:
`setDarkThemePreferred` reaches `SetPreferredAppMode` (through SWT's
`useDarkModeExplorerTheme`), but SWT 3.135.0 never calls `FlushMenuThemes` and
no public API reaches it. A native call is declined (user decision, shared with
QLogg).

A read-only `Combo`'s drop list stays light under a dark start with native
colors (`#ffffff`, `windows-2025`); a `setBackground` reaches it, so the dialog
colors (`DialogColors`) make it dark. QCaret has read-only combos only in
dialogs.

Under the dark native setting win32 draws check box labels near-white while the
main window's docks and bars stay light, so every check box comes from
`SwtThemeService.check`. It calls `DialogColors.check`, which on win32
creates the box with the theme off outside a dialog `DialogColors` colored. A
`MenuItem` or `Table` with `SWT.CHECK` has no such label and does not use it.

Measured on `windows-2025` with SWT 3.135.0 under the dark native setting
(`Win32DarkInkTest` saves the window and the readings to
`target/diagnostics/win32-dark/`):

| Drawn dark | Drawn light |
|---|---|
| Title bar, menu bar (`#303030`), push button faces (`#333333`) with white labels, read-only `Combo` | `ToolBar`, `Composite`, `Label`, `Link`, `Group`, check box and radio button panels, `Text` and a drop-down `Combo`'s field (white), `List`, `Table`, `Tree`, `Spinner`, `ProgressBar` |

`getBackground` reports the light window color for a push button as well, so an
icon inked from it is dark on dark. Icons therefore sit only on tool bars,
which draw light like the window; the main toolbar and the workspace browser's
row read `#f0f0f0` until `ChromeColors` colors them. `Text.setBackground` and
`setForeground` do color a field under the dark setting, a disabled field's
background included (disabled text stays system gray). The hint (`setMessage`)
stays the theme's gray: 87,87,87 on `Palette.DARK`'s background, measured on
Windows 11 at 150 %. The first color puts the field under the dark Explorer
theme for the life of the control; unfocused and holding text, it then draws
near-black (33,33,33) with dark text under null and explicit light colors
alike. `ChromeColors` gives the main window's fields (find and replace, the
Workspace Browser's path and filter) and trees (the Workspace Browser's and the
Outline's, with their headers) `Palette.DARK`'s colors while `ui.theme`
resolves to dark, at once after a live switch, and `FieldHint` draws a field's
hint in `Palette.DARK`'s unselected tab label color. Under the dark native
setting a field holding text keeps `Palette.DARK`'s colors on the light side
until its window is rebuilt; an empty one, such as the path field while no workspace folder is set,
takes the system list colors, set explicitly. The dark native field still draws
an empty editable field `#212121` while unfocused under those colors (OBERON G1
on `6449df6`), so `Win32EmptyField` fills it with its background from an
`SWT.Paint` listener and draws its hint in the system gray raised to 4.5:1. A
field is colored again when it becomes empty or takes text, typed or set
through `setText` alike ([dialogs and
docks](dialogs-and-docks.md#workspace-browser)). `Win32ChromeColors` reads the
native setting (`DialogColors.nativeDark`) at its first use and keeps it, so a
window created before the theme service records it follows the rule too, and a
window waiting for its rebuild keeps the setting its controls were created
under.

A tree whose side differs from the native setting (light under the dark one,
dark under the light one) keeps the native selection of that setting:
black on `#333333` (1.66:1) and black on `#17273a` (1.39:1), measured. On
`SWT.EraseItem` it draws a selected row in the palette's `selection` and
`selectionText`, raised to 4.5:1, and clears `SWT.SELECTED` and `SWT.HOT`, so
the Outline and Workspace Browser trees stay readable. The native expander keeps
the native setting's colors (white on white, `#1b1b1b` on `#1b2e44`): no public
API reaches it.

The history arrow beside a find field is an owner-drawn `Button` that SWT draws
with the scroll bar theme of the native setting at its creation
(`Display.useDarkModeExplorerTheme`, SWT 3.135.0 source), like a push button. The main window has no editable `Combo`, and dialogs use
read-only ones only; win32 SWT colors a `Combo` through `WM_CTLCOLOR`, a
read-only one by forcing the `ExtTextOut` branch (`Combo.wmColorChild`, source,
unverified on Windows).
`CTabFolderRenderer` draws the tab strip's line under the tabs and around the
selected tab in `SWT.COLOR_WIDGET_NORMAL_SHADOW` (#a0a0a0) with no API to
change it, so that 1 px line stays light under the dark palette, as do the
native list header's borders.

#### Chrome colors

`MainWindow` takes a `ChromeColors` from `PlatformTraits.chromeColors` and
calls it on every platform; `NativeChromeColors` on GTK and cocoa sets nothing.
On win32 `Win32ChromeColors` colors the main window's own surfaces while
`ui.theme` resolves to dark, at once after a live switch either way:
`Palette.DARK`'s `gutter` (`#162638`, the tab strip's) as background and its
`text` as foreground, a link its link color raised to 4.5 contrast, and
`SWT.INHERIT_DEFAULT` on each composite so push button corners match. On the
light side every such color is null. `MainWindow` registers the toolbar row
(its clip, the `ToolBar` and the More button's bar), the status bar, the find
bar, the load and print rows, the side sash with every dock under it, and each
preview's notice strip; a control created later under one takes its colors from
`SWT.Skin`.

| Control | Under dark |
|---|---|
| `Composite`, `SashForm`, `Label`, `Link`, `ToolBar`, `Canvas` (the memory gauge), check box | Palette colors |
| `Text`, `Tree` | `Palette.DARK`'s `background` and `text`; a tree header the `gutter`; the field rule above |
| Push and arrow `Button`, `Combo`, `List`, `Table`, `Spinner`, `ProgressBar`, `StyledText`, `Browser` | Unchanged, not descended into |
| `CTabFolder`, a control with `ChromeColors.PALETTE_COLORED` | Unchanged, not descended into |

A check box keeps `DialogColors.check`'s light-theme creation, so under the
dark palette its enabled label takes the palette text; a disabled label stays
system gray. win32 `ToolBar` passes its foreground to text items (Source,
Split, Preview) through `NMTBCUSTOMDRAW.clrText`, and an item's own foreground
first (`ToolBar.getForegroundPixel(ToolItem)`, SWT 3.135.0 source).

A checked tool item draws the checked background of the native setting
whatever the bar's color, measured on Windows 11: `#455160` under the dark
setting, `#c0c0c0` under the dark setting on the light side, `#cce8ff` under the
light setting. The light text (`#dce6f0`) on `#cce8ff` computes to 1.0:1, so
under the light setting `Win32ChromeColors` calls `ToolIcons.lightChecked(false)`: while the set
takes the light ink, a checked item given to `applyChecked` (Source, Split,
Preview) takes `Palette.LIGHT`'s text (`#1b2e44`, 10.9:1) as its foreground.
Code checks such an item only through `ToolIcons.check`, which sets the
selection and the ink (`MarkdownSwitch`, `ToolbarOverflow`). The main toolbar
has no checked icon item.

After each change `ChromeColors` re-inks the main toolbar's and the Workspace
Browser's `ToolIcons` from the toolbar's background and calls
`fadeDisabled`: every tool item takes the faded copy (40% opacity) as its
`setDisabledImage`. SWT's own disabled copy (`ImageColorTransformer`, gray
`0.2 * (2.9 * gray - 128) + 128`) maps the light ink `#d4d4d4` to `#e1e1e1`,
brighter than the enabled icon, so a disabled item would look enabled.
Measured by `ChromeColorsTest` on `#162638` with SWT 3.135.0 (the transformer is
shared by GTK and win32): the enabled icons 9.1 or more, SWT's copy 10.1 or
more, the faded copy 2.6 to 2.9. Menus keep the ink of their window's native
setting (`MenuBehavior.ink`, `Win32MenuBehavior`), which matches the menus.

#### Dialog colors

`DialogColors.apply` runs on every dialog shell: `Dialogs.shell` calls it
beside `inheritIcons`, and `ArchitectureTest.ownedShellsTakeDialogColors` fails
on a `new Shell` elsewhere without it. The main window and the completion popup
keep native colors. GTK and cocoa (`NativeDialogColors`) change nothing.

Under the dark native setting `Win32DialogColors` sets the colors below, forces
the body color onto children so push button corners match, and colors controls
created after the shell shows from `SWT.Skin`. A control with
`EditorPane.PALETTE_COLORED` keeps the palette. An empty, unfocused single-line
`Text` with a hint (`setMessage`) draws it in `#9a9a9a` (5.03:1) from
`Win32EmptyField`'s `SWT.Paint` listener over the native hint, which is `#6d6d6d` read-only (SWT's
`COLOR_GRAYTEXT`, 2.74:1) and `#575757` editable (1.96:1), measured on
`windows-2025` in QLogg. The dialog filters, the key capture field and
Preferences' Default workspace folder take it. `Dialogs.background` returns the
body color, so About picks the dark logo. A dialog takes the native setting at
its creation; after a switch a new dialog takes the new side. Quick Open's and
the command palette's filters and a find field's history list are dialog
controls.

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

- `Table` grid lines and header dividers (the Keys page).
- `Spinner` arrow buttons.
- Disabled `Text`, `Label` and check box text, in system gray: `#6d6d6d` on a
  field (2.7 contrast), `#808080` on the body.

- The Guide's page, which its HTML colors.

#### Dialog tabs

A native `TabFolder` keeps a light tab row and a white frame around the page
under the dark native setting, and no public API colors either. Preferences takes its
folder from `Dialogs.tabs`, which calls `PlatformTraits.dialogTabs`;
`ArchitectureTest.tabFoldersOnlyThroughDialogTabs` fails a `TabFolder` created
outside `ui.swt.platform`.

| Class | When | Folder |
|---|---|---|
| `Win32DialogTabs` | win32 under the dark native setting (`DialogColors.nativeDark`, read when the dialog is built) | A flat `CTabFolder` without a border: tab row, unselected tabs and pages `#202020`, selected tab `#2b2b2b`, tab text `#ffffff` (16.3:1 and 14.2:1) |
| `NativeDialogTabs` | win32 under the light native setting, GTK, cocoa | The native `TabFolder` |

`CTabFolder` draws the selected tab `#2b2b2b` only while focus is in the
folder, and `#202020` while a dialog button holds it (a known limit). It
outlines the selected tab, separates unselected ones and draws its selection
bar in system colors, read in package-private renderer code. Ctrl+PageDown and Ctrl+PageUp from a page
control reach either folder and wrap at the ends; Ctrl+Tab changes no tab in
either, and a mnemonic reaches the shown page's buttons only (measured on
`windows-2025` in QLogg). `DialogTabsTest` posts these keys to both folders on
win32 and fails where they differ.

### cocoa

Cocoa SWT sends no `SWT.Settings` for an appearance change and keeps the
colors it read under the system appearance at Display creation (measured on
macOS 15 and 27 in QLogg, and on macOS 15 arm64 and Intel in QCaret's LTM
spike). `CocoaThemeFollow.watch` creates `CocoaChrome` with that side and
applies the pinned one; `showOnWindows` pins each new side and passes it to
`CocoaChrome.apply`. While the pinned side differs from the one SWT read,
`CocoaChrome` sets each shell's, table's, tree's, list's and field's colors to
the pinned side, at the change, at `SWT.Show` of a shell and at `SWT.Skin` of a
new control, since the docks replace their tables while open. On the side SWT
read it sets those colors to null, the default, check boxes included.

SWT sends `SWT.Skin` from `readAndDispatch` and layout, neither of which runs
while a menu tracks, so a control a timer creates then keeps the cached side
until the menu closes (QLogg Mac lab, macOS 27.2.1: a field drew `#ffffff`
under white text). `CocoaChrome` records the side it colored each control for,
and colors a control whose record is stale at its `SWT.Show` and, while any
menu shows, at a sweep of every shell each 100 ms through the `UiDispatcher`
timer. Timers are the only work measured to run during tracking on both macOS
15 hosts: on macos-15-intel the new field drew with no SWT paint event and a
posted `asyncExec` did not run before the menu closed. A menu bar sends
`SWT.Show` with its shell and no `SWT.Hide`, so it does not start the sweep.

Table and tree headers never have null colors: a null header in dark draws
blank labels. From `SWT.Skin` at creation on, `CocoaChrome` gives each header
the list background and text color of the current appearance; in the startup
appearance those are the Display's `COLOR_LIST_BACKGROUND` and
`COLOR_LIST_FOREGROUND`, the colors SWT reports for a header with none set. A
`Table` header repaints new colors only when shown again (`redraw` leaves the
area right of the last column stale), so after a change `CocoaChrome` hides and
shows a visible one. A `Tree` header repaints at once. Measured on macOS 27.2
with SWT 3.135.0.

- A control with `EditorPane.PALETTE_COLORED` keeps its colors: the editor,
  Basic Markdown preview, compare and replace preview `StyledText` and the tab
  strip, which the palette owns and nothing would restore.
- `CocoaChrome`'s window, list and text colors are constants measured on macOS
  15; a control class `walk` does not list keeps SWT's colors behind pinned
  AppKit text, so a new control type needs a row there.
- The control types QCaret creates that `walk` leaves alone: `Composite`,
  `SashForm`, `ToolBar`, `Label` and push buttons take the forced shell
  background, and AppKit draws their text in the pinned appearance; a `Canvas`
  paints itself (`TabStripButtons` and `OverviewStrip` from the palette,
  `MemoryGauge` raising its ink against the background it inherits); a `Browser`
  shows its page. Unverified on macOS: a `Link`, whose text is all link, in
  AppKit's link color, and `ProgressBar` and `Slider`, which AppKit draws.
- `ReplaceDialog` marks its computing note with a style range, because
  `setForeground(null)` returns a field to the startup color.

## Palette application

`MainWindow` applies a palette to the `StyledText` colors, the editors' painted
marks (margin, indent guides, modified-line marks) and the tab strip, then
re-inks its `ToolIcons` on the next UI turn, after native controls have
restyled. Only the selected tab's editors take the palette at once; another
tab's take it when the tab is next selected (`EditorTab.setPalette`, on
`SWT.Show` of the tab's frame, before it paints). Each `StyledText` color
change resets the widget's line cache over the whole document, so a hidden
tab's recolor would lengthen a theme switch's settings turn by its length; a
window a rebuild replaces never recolors its hidden tabs.

`ui.editorColors` can put the palette on the other side from native controls,
so every surface takes its colors from one of the two, never a mix:

| Colors from | Surfaces |
|---|---|
| `Palette` | Editor text, gutter, caret, overview strip, tab strip, compare window views, replace preview |
| Native theme | Menus, toolbar and its icons, docks, find bar, status bar, dialogs, title bars, About logo; under dark on win32 `ChromeColors` colors the bars and docks, `DialogColors` the dialogs |

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
(`MainWindow.logoVariant`): native chrome can differ from the palette in a win32
window waiting for its rebuild and on a GTK theme with no dark variant.

## Tab colors

`TabStrip.colorTabs` sets all four `CTabFolder` colors, because the folder
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

On GTK 4, `ToolIcons.apply` calls `ToolItemSizing.keepIconSize`, which in
`Gtk4ToolItemSizing` turns off shrinking on a tool item's GtkPicture through SWT
internals (`ToolItem.imageHandle`, `GTK4.gtk_picture_set_can_shrink`). SWT
3.135.0 wraps that picture in a box that measures 0 high for any width under
GTK 4.14, so the tool bar lays an image-only item out at its padding, 12 px,
and shows no icon. If an SWT upgrade renames either internal, the items fall
back to that layout and `ToolbarAndMenusTest.everyButtonIsWideEnoughForItsIcon`
fails in the GTK 4 lane.

| Platform | Item image | Ink | Items without an icon |
|---|---|---|---|
| GTK 3 | `MenuItem.setImage` | Same reading as the toolbar, live switch included | Hold `ToolIcons.blank` in a menu with an icon, because GTK 3 SWT reserves no image column and the label would move 22 px |
| GTK 4 | SWT ignores `setImage` | - | - |
| win32 | `MenuItem.setImage` | Fixed from `SwtThemeService.nativeDark`: menus take the native setting of their window's creation, and the toolbar stays light under a dark one | Bare; the system reserves the column |
| cocoa | None; the application menu stays native | - | - |

A disabled GTK menu item shows SWT's disabled copy of its image, which keeps
the alpha and raises the ink's brightness. On a dark background
`ToolIcons.showState` gives it the image `MenuBehavior.disabledOnDark` returns,
the faded copy on GTK; `MainMenuBar` and `EditorMenu` call it after changing an
item. A disabled GTK tool item on a dark background shows the faded copy too:
`GtkChromeColors` turns on `ToolIcons.fadeDisabled` for the main toolbar's and
the Workspace Browser's icon sets. Check, radio, cascade and listing items show no
icon: GTK draws a check item's image beside the indicator, and on Windows 11
SWT draws it in the indicator's place. The tab menu is rebuilt on each show and
carries none.
