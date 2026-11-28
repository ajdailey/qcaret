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

Popup menus (menu bar menus, cascades, context menus) follow a View > Theme
switch and a Windows mode change under System through `Win32MenuThemes.flush`,
which `Win32ThemeFollow.showOnWindows` calls after `NativeTheme.set`. The popup
menu theme is process-wide: `setDarkThemePreferred` reaches
`SetPreferredAppMode` (through SWT's `useDarkModeExplorerTheme`), and uxtheme
keeps the menus in their first side until `FlushMenuThemes` runs, which SWT
3.135.0 never calls and no public API reaches. `Win32MenuThemes` calls it as
uxtheme ordinal 136 (no exported name) through `java.lang.foreign` by
reflection, the API being a preview in Java 21: `LoadLibraryA("uxtheme.dll")`,
then `GetProcAddress` by ordinal, linked once per process. Measured on
`windows-2025`: the popup background `#f9f9f9` after a switch to dark without
the flush, `#2c2c2c` after it, and back. A link or call that fails logs one
WARN, and menus keep the side of the start (`NativeTheme.startDark`).

A read-only `Combo`'s drop list stays light under a dark start with native
colors (`#ffffff`, `windows-2025`); a `setBackground` reaches it, so the dialog
colors (`DialogColors`) make it dark. QCaret has read-only combos only in
dialogs.

Under the dark native setting Windows 11 build 26300 draws the selected and
hover row of a drop-down combo's open list white on white, read-only or
editable, at 100 % and 150 %, whatever the combo's colors and under a light or
dark system setting; `windows-2025` draws it `#0078d4` with white text. Neither
`SetWindowTheme` on the list nor the `WM_CTLCOLORLISTBOX` answer changes that
row (`Win32ComboLeverProbe`, measured on both). Every combo is created with
`ComboListSelection.draw`: `Win32ComboListSelection` subclasses the
list window through `Win32ListBox` (SWT's `internal.Callback` and
`internal.win32.OS`, a listed `ArchitectureTest` exception) and, while the dark
native setting is on, paints
the selected row in `Palette.DARK`'s selection with white text after each
`WM_PAINT` and `WM_TIMER`, after each selection move, which the list draws
without one, and into the `WM_PRINTCLIENT` DC. The opening slide renders the
list through `WM_PRINT`/`WM_PRINTCLIENT` and shows it before any `WM_PAINT`
(traced at 150% on build 26300), so the hook also posts the list a private
message (`WM_APP + 0xf42`) when it shows and invalidates the list when that
arrives; a combo `SWT.Selection` on the open list (a keyboard move) invalidates
it too. The setting is read per message, so a live switch applies at the next
paint; under the light setting the native selection stays. After a live switch
without a window rebuild (a dialog open during the switch), the list keeps its
light ground and text and only the selected row is dark, as other native
controls keep their creation theme ([theme
rebuild](main-window.md#theme-rebuild)). Disposing the combo restores the
window procedure and frees the callback. If an SWT upgrade drops a member, one
WARN is logged, the native selection stays, and `Win32ListSelectionTest`
fails on win32.

Under the dark native setting Windows 11 draws the selected row of a `List`
solid `#ffffff` below white text, focused or not (measured on QLogg, OBERON, at
150% and 100%); `windows-2025` draws it `#0078d4` with white text. Every `List` is created with `Dialogs.list`, which calls
`ListSelection.PLATFORM.draw`; `ArchitectureTest.listsOnlyThroughDialogs`
rejects `new List` elsewhere. `Win32ListSelection` subclasses the ListBox
window through `Win32ListBox` and, when `Win32DialogColors` colored the list's
dialog dark, paints the selected row `#626262` with focus and
`Win32UnfocusedSelection.BACKGROUND` (`#747474`) without, white text, as a dark
dialog tree draws its rows. It paints after each `WM_PAINT`, into the
`WM_PRINTCLIENT` DC, and after any message that moves the selection or the
focus. The side is taken once, when `Dialogs.list` creates the list: a dialog
keeps the colors it was created with across a live switch, so a list in a dark
dialog stays painted and one in a light dialog stays native. A combo list reads
`DialogColors.nativeDark` per message instead, since main-window combos follow
a live switch. The painted row covers
the native focus outline. Disposing the list restores the window procedure; a
`SWT.MULTI` list is left native. If an SWT upgrade drops a member, one WARN is
logged, the native selection stays and `Win32ListSelectionTest` fails on win32.
`NativeListSelection` does nothing on GTK and cocoa.

Under the dark native setting a selected row in a table or tree without focus
draws `#252525` on the `#2b2b2b` field, 1.1:1. `Win32UnfocusedSelection` draws
it `#747474` with white text (3.03:1 on the field, text 4.67:1) in every dark
table and tree `Win32DialogColors` and `Win32ChromeColors` color; a focused
list keeps the native `#223e55`.

A check box is a plain `Button` with `SWT.CHECK`, created under the native
setting. Under the dark one win32 draws the box dark (unchecked interior
`#242424`) and its label near-white, in a dialog and in the dark main window
alike; the light chrome gives the label system text ([chrome
colors](#chrome-colors)).

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
Workspace Browser's path and filter), trees (the Workspace Browser's and the
Outline's) and tables (Search Results and Bookmarks), with their headers,
the dark chrome colors while `ui.theme` resolves to dark, at once after a live
switch ([chrome colors](#chrome-colors)). Under the dark native setting a field
holding text keeps them on the light side until its window is rebuilt; an empty
one, such as the path field while no workspace folder is set, takes the system
list colors, set explicitly. The dark native field draws an empty editable
field `#212121` while unfocused under any colors (measured on Windows 11), so
while a field is empty and unfocused `Win32EmptyField` fills it with its
background from an `SWT.Paint` listener and draws its hint: under dark in
`Win32DialogColors.HINT` (`#9a9a9a`, 5.03:1 on `#2b2b2b`), on the light side
under the dark native setting in the system gray raised to 4.5:1. A
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
`SWT.EraseItem` a tree or table on the other side draws a selected row in the
palette's `selection` and `selectionText`, raised to 4.5:1, and clears
`SWT.SELECTED` and `SWT.HOT`, so the dock lists stay readable. The native expander keeps
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

Header colors make SWT 3.135's win32 header custom draw paint the text, with
`DrawText` and no `DT_END_ELLIPSIS`, so a column narrower than its text would
clip it. `Win32HeaderText.follow` runs wherever `Win32ChromeColors` and
`Win32DialogColors` set header colors: at each column resize it sets the column
text shortened to the width less the 6 px insets with `...`, and the whole text
as the column's tool tip. `HeaderTexts.full` returns the whole text, which
`TableFit.spread` measures (`Win32HeaderTextTest`).

#### Chrome colors

`MainWindow` takes a `ChromeColors` from `PlatformTraits.chromeColors` and
calls it on every platform; `NativeChromeColors` on GTK and cocoa sets nothing.
On win32 `Win32ChromeColors` colors the main window's own surfaces while
`ui.theme` resolves to dark, at once after a live switch either way, in the
neutral grays of `Win32DialogColors` whatever the `ui.editorColors` scheme:
`WINDOW` (`#202020`, the dark title bar's) as background and `TEXT`
(`#ffffff`, 16.29:1) as foreground, a link its link color raised to 4.5
contrast, and `SWT.INHERIT_DEFAULT` on each composite so push button corners
match. On the light side every such color is null. `MainWindow` registers the toolbar row
(its clip, the `ToolBar` and the More button's bar), the status bar, the find
bar, the load and print rows, the side sash with every dock under it, and each
preview's notice strip; a control created later under one takes its colors from
`SWT.Skin`.

| Control | Under dark |
|---|---|
| `Composite`, `SashForm`, `Label`, `Link`, `ToolBar`, `Canvas` (the memory gauge), check box | `WINDOW` and `TEXT` |
| `Text`, `Tree`, `Table` | `FIELD` (`#2b2b2b`, 14.16:1) and `TEXT`; a tree or table header `WINDOW`; a tree with columns also a one-pixel `FIELD` background image; the field rule above |
| Read-only `Combo` | `FACE` (`#333333`, the push button face, 12.63:1) and `TEXT`: the dark native theme draws its closed field and leaves its drop-down list white without colors |
| Push and arrow `Button`, editable `Combo`, `List`, `Spinner`, `ProgressBar`, `StyledText`, `Browser` | Unchanged, not descended into |
| `CTabFolder`, a control with `ChromeColors.PALETTE_COLORED` | Unchanged, not descended into |

win32 SWT fills a sorted column's cells and the area below the last row in
the background plus 8 per channel (`Tree.getSortColumnPixel`, SWT 3.135.0
source), `#333333` on `FIELD`, unless the tree has a background image. The
Workspace Browser sorts its Name column, so without the image that column
draws `#333333`, measured on Windows (`windows-2025`, `windows-11-arm`); the
light side keeps the native tint (`#f7f7f7` on white). The image leaves the
dark native selection unchanged (`#626262`, measured).

Under the dark chrome a check box's enabled label takes `TEXT`. On the light
side under the dark native setting, a window waiting for its rebuild, an enabled
label takes `COLOR_WIDGET_FOREGROUND`, since the dark native box draws it
near-white without colors; a disabled label stays light gray. win32 `ToolBar` passes its foreground to text items (Source,
Split, Preview) through `NMTBCUSTOMDRAW.clrText`, and an item's own foreground
first (`ToolBar.getForegroundPixel(ToolItem)`, SWT 3.135.0 source).

A checked tool item draws the checked background of the native setting
whatever the bar's color, measured on Windows 11: `#455160` under the dark
setting, `#c0c0c0` under the dark setting on the light side, `#cce8ff` under the
light setting. `TEXT` on `#cce8ff` computes to 1.27:1, so
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
Measured by `ChromeColorsTest` on `#202020` with SWT 3.135.0 on `windows-2025`
and `windows-11-arm`: the enabled icons 9.64 or more, SWT's copy 10.88 or more,
the faded copy 2.67 to 2.92. Menu icons take the ink of the side the popup
menus show (`MenuBehavior.ink`, `Win32MenuBehavior`,
`Win32MenuThemes.shownDark`); ink from the window's side on menus of the other
side gave `#3e3e3e` on `#2c2c2c` (1.31; measured on Windows 11 at 150 %).

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

A table with grid lines calls `Dialogs.gridLines` in place of
`setLinesVisible(true)`; `ArchitectureTest.gridLinesOnlyThroughDialogColors`
fails a `setLinesVisible` call outside `ui.swt.platform`. The win32 list view
draws native grid lines in `COLOR_3DFACE`, `#f0f0f0` under dark (measured in
QLogg, Windows 11 at 150 %), and no public API colors them. In a dialog
`Win32DialogColors` colored, `gridLines` turns the native lines off and
`Win32GridLines`, an `SWT.Paint` listener, draws `#404040` rules (1.37:1 on
`#2b2b2b`; light lines are 1.14:1 on white) along the last pixel column of each
column and one pixel above each shown item's top (`TableItem.getBounds`). Rules
below the last item step by the last two items' pitch. A multiple of
`getItemHeight` is wrong at a fractional zoom: a 29 px row at 150 % is no whole
number of points, so such rules fall half a pixel behind per row and cross the
text by row 12 (measured in QLogg). Elsewhere the native lines show. The Keys page and Keyboard Shortcuts show
grid lines.

SWT draws the header dividers of a table with header colors in `COLOR_3DFACE`
unless the Display data key
`org.eclipse.swt.internal.win32.Table.headerLineColor` holds a color. `apply`
sets it through `Win32HeaderLines`: `#404040` under dark, cleared under light
once no dark dialog is open. The key is Display-wide, and only a dark dialog's
tables have header colors. This is a listed Public SWT API exception;
`ArchitectureTest.headerLineKeyOnlyInWin32HeaderLines` keeps the key in that
class. If an SWT upgrade drops the key, the dividers draw light and
`DialogGridLinesTest` fails on win32.

Public API leaves these light or gray:

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
| `NativeDialogTabs` | win32 under the light native setting, GTK | The native `TabFolder` |
| `CocoaDialogTabs` | cocoa | The native `TabFolder`, keeping keyboard focus on a control across a tab change |

`CTabFolder` draws the selected tab `#2b2b2b` only while focus is in the
folder, and `#202020` while a dialog button holds it (a known limit). It
outlines the selected tab, separates unselected ones and draws its selection
bar in system colors, read in package-private renderer code. Ctrl+PageDown and Ctrl+PageUp from a page
control reach either folder and wrap at the ends; Ctrl+Tab changes no tab in
either, and a mnemonic reaches the shown page's buttons only (measured on
Windows 11 at 150 %). `DialogTabsTest` posts these keys to both folders on
win32 and fails where they differ.

On cocoa SWT hides the old page on a tab change, and when no ancestor of the
focused control takes focus, AppKit's first responder becomes the window:
`NSTabView` takes no focus with Full Keyboard Access off. The next Ctrl+PageDown
then reaches no control. `CocoaDialogTabs` runs on each tab change: when no
visible control has focus, the new page's first control that takes focus gets
it, else a childless 1x1 `Canvas` below the pages, whose page traversals reach
the folder. A page of check boxes (Preferences > View) has no control that
takes focus there.

### cocoa

Cocoa SWT sends no `SWT.Settings` for an appearance change and keeps the
colors it read under the system appearance at Display creation (measured on
macOS 15 and 27 in QLogg, and on macOS 15 arm64 and Intel in QCaret). `CocoaThemeFollow.watch` creates `CocoaChrome` with that side and
applies the pinned one; `showOnWindows` pins each new side and passes it to
`CocoaChrome.apply`. While the pinned side differs from the one SWT read,
`CocoaChrome` sets each shell's, table's, tree's, list's and field's colors
(read-only `Combo` excepted, below) to the pinned side, at the change, at
`SWT.Show` of a shell and at `SWT.Skin` of a new control, since the docks
replace their tables while open. On the side SWT read it sets those colors to
null, the default, check boxes included.

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
- A read-only `Combo`, an `NSPopUpButton`, keeps null colors on every side.
  SWT writes a Combo's foreground into each item title of its menu, and AppKit
  draws that menu in the pinned appearance, where an explicit white title draws
  `#656565` on `#323232` (2.2:1). With no color, the closed title draws
  `#e8e8e8` on `#656565` and the items `#e0e0e0` on `#323232` under a dark pin,
  `#262626` on white and `#242424` on `#ececec` under a light pin, the native
  colors. Measured on macOS 15 arm64 and Intel with SWT 3.135.0.
- AppKit draws menus and the menu bar whole, so they stay readable. Only the
  read-only `Combo` menu is measured under a pin: it takes the pinned side.

## Palette application

`MainWindow` applies a palette to the `StyledText` colors, the editors' painted
marks (margin, indent guides, modified-line marks) and the tab strip, then
re-inks its `ToolIcons` on the next UI turn, after native controls have
restyled. Only the selected tab's editors take the palette at once; another
tab's take it when the tab is next selected (`EditorTab.setPalette`, on
`SWT.Show` of the tab's frame, before it paints). Each `StyledText` color
change resets the widget's line cache over the whole document, so a hidden
tab's recolor would lengthen a theme switch's settings turn by its length; a
window a rebuild replaces recolors no tab unless the rebuild waits ([theme
rebuild](main-window.md#theme-rebuild)).

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
| Selected, focus inside the folder | `background` | `text` |
| Selected, focus in a dock or another shell | `gutter` | `text` |
| Unselected | `gutter` | `text` |

`CTabFolder` draws the selection background only while focus is inside the
folder and keeps one selection foreground for both cases, so that foreground
must read on `gutter` and on the selection background. `text` reads at 4.5:1 or
more on both in every bundled scheme (`PaletteTest`); the lowest are Sandstone
(4.63 on `gutter`) and Nightwater (4.80).

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
| win32 | `MenuItem.setImage` | Fixed from `Win32MenuThemes.shownDark` (`MenuBehavior.ink`): the side pop-up menus show, the last flushed side or else the start side; the toolbar stays light under a dark one | Bare; the system reserves the column |
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
