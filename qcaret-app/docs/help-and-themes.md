# Help, themes and fonts

The Help commands, the user guide build, UI text and logs (`app.controller`,
`app.about`, `app.i18n`, `app.log`), and themes and font discovery
(`app.theme`), of [qcaret-app](../README.md).

## Help commands

`HelpController` runs the Help commands.

| Command | Effect |
|---|---|
| Keyboard Shortcuts | Opens the view's filtered table of the `Keymap` |
| Command-Line Options | Shows the usage text |
| User Guide | Copies the guide page from the jar to `<settings>/cache/guide.html` on a worker and calls `MainView.showGuide`. When the view has no browser, or closes its window after a browser failure (`showGuideElsewhere`), the system's HTML application opens the copy, else a notice names the file |
| Show Log | Shows the `RecentLog` lines and clears the status-bar marker, which a WARN or ERROR line on any thread shows through one posted task at a time |
| Debug Log File | Attaches or detaches the log file on a worker and saves `log.debugFile`; its check follows `LogFiles.attached` |
| Open Log Folder, Open Settings Folder | Create the folder owner-only when missing |
| Report an Issue | Copies the About details to the clipboard and opens the issue page. With the project link in About, it is QCaret's only web address, opened only when the user picks it |
| About, Licenses | Read on workers; `showLicenses` reads `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` from the jar and shows them through `MainView.showText` |

## View settings from the controller

- A theme change pushes the palette. When native controls show the change only
  at the next start (`ThemeService.showsNow`), the view shows a notice.
- `Settings` queues each change for a worker; see
  [Settings](settings.md#running-settings).
- Zoom In and Zoom Out step `editor.fontSize` one point, from 4 to 72; Reset
  Zoom sets it to 0, the platform's default. The view changes the font in
  place. A change of `editor.fontFamily` reaches the view through
  `MainView.setFontFamily`.
- View > Right Margin toggles `ui.rightMargin`; `MainView.setRightMargin`
  passes every editor `editor.rightMargin` while it is on, else 0, and again
  when either setting changes.
- `EditorAids` holds View > Indent Guides (`ui.indentGuides`) and View >
  Modified Line Marks (`ui.lineChanges`), passed through
  `MainView.setIndentGuides` and `MainView.setLineChangesShown` at start and on
  a change, and runs Edit > Wrap Paragraph at `editor.rightMargin`.
- `PreferencesInput.loadChoices` lists the theme files and the monospace
  families on a `qcaret-io-` worker; the dialog fills its lists when they
  arrive.

## UI text, About and logs

`UiText` names every string the UI shows; the English bundle
`messages.properties` holds them, and `ArchitectureTest.everyUiTextIsUsed`
fails on a constant nothing reads. `AboutInfo` collects the version, Java, OS,
toolkit and settings directory for About and Copy Details.

`RecentLog` keeps the last log lines for the launcher's `RecentHandler`,
`LogFiles` is the debug log file's contract, and `EventLoopWatchdog` reports
UI-thread stalls at `-d 2` and above.

## User guide build

The build converts `docs/user-guide.md` to `app/guide/guide.html` in this
module's jar. `src/build/GuideRenderer.java` runs at `generate-resources` with
commonmark and its tables extension, which `maven-dependency-plugin` copies to
`target/guide-tools`, so they stay off every class path.

- Each PNG is embedded as a `data:` URI.
- Each heading gets a plain `HtmlInline` `<a id>` with its GitHub anchor; a
  subclass of `HtmlInline` renders as nothing.
- A link leaving the page, a `#link` naming no heading and an image that is not
  a PNG beside the guide fail the build.

## Themes

`ThemeMode` is `ui.theme`: light, dark or system. `ui.editorColors` names an
editor color scheme; `EditorSchemes` holds each scheme's name, Preferences label
and palette (follow, light, dark), and a new scheme is an entry there.
`ThemeController` resolves the `Palette` through `EditorSchemes.palette`: follow
takes `ui.theme`'s side, system following `ThemeService.systemDark` and its
change events, and an unknown name resolves as follow. It pushes a changed
palette to its `PaletteListener`s.

`ThemeService.preferDark` takes `ui.theme`'s side only. `setEditorColors`
changes the palette and never native controls, so it shows no restart notice;
under the light or dark scheme a system change moves native controls and leaves
the palette.

`Palette` is the editor color scheme as toolkit-free `Rgb` values: text,
background, selection, current line, gutter and line numbers, caret, whitespace
marks, the find-match background, the comparison window's row backgrounds
(`diffRemoved`, `diffAdded`, `diffChanged`), the unselected tab label
(`tabText`), the right margin line (`rightMargin`), the indent guides
(`indentGuide`), the modified-line marks (`lineAdded`, `lineChanged`,
`lineRemoved`, each at a contrast ratio of 3 on the background and the current
line), and a `TokenStyle` (color,
bold, italic, underline) for every `TokenRole`; a grammar token type paints
with the role of the same name. The light and dark defaults derive from the
brand navy `#1B2E44`, cyan `#56C1F0` and amber `#F3B65B`; every token color
reaches a contrast ratio of 4.5 on the background, the current line and a find
match (`PaletteTest`).

### Theme files

`ThemeColors` is a theme file, `<settings>/themes/<name>.theme`, of
`light.<color> = #RRGGBB` and `dark.<color> = #RRGGBB` lines;
`docs/user-guide.md` lists the colors.

- A known key with a bad value logs one WARN and keeps the built-in color; any
  other line is ignored.
- A file over 64 KiB, missing or unreadable logs one WARN and sets nothing.
- A token color keeps its role's font style.
- `over` lays the colors on a built-in palette and returns that palette itself
  for a side with no colors, so `Palette.LIGHT` and `Palette.DARK` stay the
  values in use without a file.
- The side applied is the palette's, so the dark scheme under a light
  `ui.theme` takes the `dark.*` lines.

`Gui` reads the chosen file before the Display and resolves the first palette
from both settings, so the first paint has its colors. After that
`WindowSet.loadTheme` reads it on a `qcaret-io-` worker when
`ui.themeFile` changes and on each Preferences apply that keeps it, and
`ThemeController.setColors` pushes the palette if it differs; a read that a
newer one replaced is dropped.

## Font discovery

`FontFiles` lists the monospace families for Preferences. `Display.getFontList`
and any measuring GC run only on the UI thread (`Device.checkDevice`), so a
worker reads font files instead, with no toolkit. It opens `.ttf`, `.otf`,
`.ttc` and `.otc` files and reads the `cmap`, `hhea`, `hmtx` and `name` tables.
The check is the same on every platform; only the folders differ:

| Platform | Folders |
|---|---|
| Linux | `~/.fonts`, `$XDG_DATA_HOME/fonts`, `fonts` under each `$XDG_DATA_DIRS` |
| Windows | `%WINDIR%\Fonts`, `%LOCALAPPDATA%\Microsoft\Windows\Fonts` |
| macOS | `/System/Library/Fonts`, `/Library/Fonts`, `~/Library/Fonts` |

A face is monospace when `i`, `W` and `0` have glyphs with one nonzero advance.
`post.isFixedPitch` is not read: Noto Color Emoji sets it with no letters, and
FreeMono and Noto Sans Mono CJK leave it clear. A face whose family differs
from its typographic family (name IDs 1 and 16) is one weight of that family
and is left out. Bitmap and Type 1 fonts are not listed.
