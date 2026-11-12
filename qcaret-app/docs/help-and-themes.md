# Help, themes and fonts

The Help commands, the user guide build, UI text and logs (`app.controller`,
`app.about`, `app.i18n`, `app.log`), and themes and font discovery
(`app.theme`), of [qcaret-app](../README.md).

## Help commands

`HelpController` runs the Help commands; `TipOfTheDay` runs Tip of the Day.

| Command | Effect |
|---|---|
| Keyboard Shortcuts | Opens the view's filtered table of the `Keymap` |
| Command-Line Options | Shows the usage text |
| User Guide | Copies the guide page from the jar to `<settings>/cache/guide.html` on a worker and calls `MainView.showGuide`. When the view has no browser, or closes its window after a browser failure (`showGuideElsewhere`), the system's HTML application opens the copy, else a notice names the file. When `SettingsLocation.confined` holds (explicit and portable mode on Linux), it skips `showGuide` and runs `showGuideElsewhere`: WebKitGTK writes `~/.cache/QCaret` and `~/.local/share/QCaret` and Mesa `~/.cache/mesa_shader_cache` outside the settings directory |
| Tip of the Day | Opens one modeless dialog per window, or raises it. `TipList` holds the tips as `UiText` patterns, each with the command it names; the command's first key comes from the window set's `Keymap` at show time, and a command without a key shows none. Each tip shown saves `help.nextTip`, wrapping, so the next show in any window continues the list. With `help.tipsOnStartup` on, the dialog opens after the session restore, once `RecoveryController.afterOffer` reports the recovery offer ended, and waits while `MainView.promptShown` reports a modal dialog in any window, checking every 500 ms. A launch that hands its files to a running instance opens no window, so it shows no tip |
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
  a change, and runs Edit > Line > Wrap Paragraph at `editor.rightMargin`.
- `PreferencesInput.loadChoices` lists the theme files and the monospace
  families on a `qcaret-io-` worker; the dialog appends the files to Editor
  colors and fills the font list when they arrive.

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
editor color scheme or a theme file (`Appearance.colors`); `EditorSchemes` holds
each scheme's name, Preferences label and palette, in list order: follow, light,
dark, then the nine `BundledSchemes`. A new scheme is an entry in both.
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
bold, italic, underline) for every `TokenRole`; a grammar names token types,
never colors, and `TokenCache` paints each type with the role of the same name,
so a scheme colors every grammar. The light and dark defaults derive from the
brand navy `#1B2E44`, cyan `#56C1F0` and amber `#F3B65B`.

`PaletteTest` holds every scheme to these ratios:

| Colors | On | Ratio |
|---|---|---|
| Text, every token | Background, current line, find match, occurrence, bracket | 4.5 |
| Selected text | Selection, gutter | 4.5 |
| Unselected tab label | Gutter | 4.5 |
| Text | Comparison rows | 4.5 |
| Line numbers, bookmark mark | Gutter | 3 |
| Caret | Background | 3 |
| Modified-line marks | Background, current line | 3 |

`BundledSchemes` start from published terminal palettes and plain pairs; a
color below its floor moves toward black on a light background or white on a
dark one, only as far as the floor needs. No palette file is copied.

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
- A file is chosen in place of a scheme (`Appearance` holds it with follow), so
  the side applied is the one `ui.theme` resolves to.
- `ThemeColors.list` leaves out a file named like a scheme in any case, since
  `ui.editorColors` reads a scheme name as the scheme.

`Gui` reads the chosen file before the Display and resolves the first palette
from both settings, so the first paint has its colors. After that
`WindowSet.loadTheme` reads it on a `qcaret-io-` worker when the chosen file
changes and on each Preferences apply that keeps it, and
`ThemeController.setColors` pushes the palette if it differs; a read that a
newer one replaced is dropped. Choosing a scheme clears the file's colors on the
UI thread before the scheme applies.

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
