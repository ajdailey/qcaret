# Settings

The settings directory, `qcaret.conf`, the running `Settings` copy and key
binding overrides, in `app.config` and `app.controller` of
[qcaret-app](../README.md). The directory modes and the files beside
`qcaret.conf` are in the root `README.md`.

## Settings directory

`SettingsLocation` picks the settings directory: `--settings <dir>` (explicit),
the installation directory `qcaret.home` when it holds `qcaret.conf`
(portable), else on Linux `<qcaret.home>/bin` when `qcaret.conf` sits beside
the `bin/qcaret` script (portable, `OsTraits.launcherDirectory`), else the
per-user directory of the OS. The bootstrap applies the
same order to the SWT native cache. `SettingsLocation.confined` is true in
explicit and portable mode on Linux; there `PlatformSetup` sets `user.home` to
the settings directory and User Guide opens without the embedded browser
([Help commands](help-and-themes.md#help-commands)).

## Preferences record

`Preferences` holds `ui.theme`, `window.*`, `log.debugFile` (attaches the debug
log file at the next start), `Options` (the Preferences dialog's settings),
`UiState` (search toggles, histories, recent grammars, dialog sizes, the
toolbar, right margin, indent guide and modified-line mark choices, the last
date format, and `ViewDefaults`, the display state a window starts from),
`ShortcutOverrides`
and `Appearance` (`editor.fontFamily`, and `ui.editorColors` as a scheme or a
theme file).
`TipSettings` holds `help.tipsOnStartup` and `help.nextTip`.
`PreferenceKeys` maps them to and from `qcaret.conf`. A new setting is a record
component and its lines in `PreferenceKeys`, plus a `PreferencesForm` field when
the dialog shows it.

## Settings file

`ConfigStore` has two blocking calls, serialized by a lock. Both run off the UI
thread, apart from the first load before the Display:

| Call | Effect |
|---|---|
| `load()` | Reads the file |
| `reload()` | As `load`, but null for a file that cannot be read or parsed |
| `save(before, after)` | Re-reads the file and writes only the keys that differ between the two settings, so another process's newer values and unknown keys stay. No difference writes nothing. A missing or malformed file gets every key of `after` |

`merge(base, theirs, mine)` applies the keys that differ from `base` to
`theirs` over `mine`, except keys `mine` also changed. The file is Java
properties, ISO-8859-1, sorted keys, no date comment, `version=1`.

| Condition | Behavior |
|---|---|
| File missing | Defaults |
| No `version` key | Read as version 1, so an empty portable marker is valid |
| Unreadable, over 1 MiB, does not parse, or another version | Malformed: defaults, one WARN, and the first save copies it to `qcaret.conf.invalid` before replacing it |
| A value that does not parse or is out of range | That value's default, one WARN naming the key; the file is not malformed |
| Read-only directory | Saving disabled with one WARN |
| A save over 1 MiB | Refused with one WARN; the file stays |
| Malformed since the last load | As above: the save keeps it as `qcaret.conf.invalid` |
| Per-user directory missing | Created owner-only by the first save |

Writes go through `AtomicFiles`. Only file names and keys are logged, never
values. No file lock is held: two processes saving within one read and write
can lose one of the two.

## Running settings

`Settings` (`app.controller`) is the one running copy. Every controller reads
`current()`, apart from the View display toggles: word wrap, the marks, the
overview strip, line numbers and the workspace browser are per-window state,
and `ui.wordWrap` and its siblings set only what a window starts with ([Window
display defaults](sessions-and-recovery.md#window-display-defaults)). `set` replaces the
copy, calls the listeners on the UI thread and queues the change for a
`qcaret-lifecycle-` worker. Workers drain the queue in order under `saveLock`,
which the UI thread never takes, as one `save` from the first change's `before`
to the last change's `after`. `ApplicationController.apply` is the listener
that brings each running window in line, whatever the change's source.

`refresh` drains the queue, loads the file on a worker and takes up what
another process changed; a change made meanwhile survives through `merge`. A
failed save, a read-only directory or a file that cannot be read or parsed
keeps the running settings. `takeUnreadable` is true once per run after a
refresh found the file unreadable while saving is on; `PreferencesController`
then shows a notice when Preferences opens or Clear History runs.

## Preferences dialog

`PreferencesController` runs `refresh` before the dialog opens, so the dialog
shows the file's values, and hands checked fields to `set`. A failed save shows
a notice, and the settings stay applied for the run. Every setting applies to
the running windows; none needs a restart. On the View tab, the six per-window
boxes change only the defaults, and Toolbar, Right margin, Indent guides and
Modified line marks apply to every window, as their View menu items do. Apply
writes only the View boxes changed since the dialog opened or the last Apply
(Restore Defaults counts), so a View menu toggle made while the dialog shows
keeps its value. The
Editor tab's new-file lists leave open documents alone.

`clearHistory` empties every `search.history.*.N` list and `SearchController`'s
last run at once, apart from `apply`, so Cancel does not restore them. It runs
`refresh` first, so another process's entries go too. Retained searches leave
`qcaret_session.conf` at the same press (see [Sessions and
recovery](sessions-and-recovery.md#clear-history)). An open find bar's list
follows; its field texts stay. A failed save shows
`PREFERENCES_HISTORY_NOT_REMOVED`: the lists stay empty for the run and the
entries stay in the file. `PreferencesController` keeps the settings the file
holds, and `Settings.setFrom` saves from them on the next press, which `set`
would skip because the running settings already match.

## Key binding overrides

`Keymap.withOverrides` replaces a command's keys from `ShortcutOverrides`. A
key takes the scope of the command's default keys, or window scope for a menu
command without any. `ShortcutOverrides.parse` refuses a value with a stroke
that does not parse or that types text (`KeyStroke.types`), with one WARN
naming the key; a key naming no command stays in the file. `KeyStroke.stored`
is the platform-free form: `Mod+Alt+K`. The dialog refuses a key another
command holds. `Keymap.conflicts`, such as a conflict written by hand, is
logged at start and on a change and listed at the end of Help > Keyboard
Shortcuts.

## Keys

| Key | Values | Default | Set from |
|---|---|---|---|
| `ui.theme` | `light`, `dark`, `system` | `light` | View > Theme |
| `ui.editorColors` | An editor color scheme (`follow`, `light`, `dark`, `sandstone`, `nightwater`, `garden-light`, `garden-dark`, `legal-pad`, `paper`, `midnight`, `phosphor`, `graphite`), or the name of a file in `themes/` without `.theme`, laid over `follow`'s palette. It colors the editors, tab strip, compare windows and replace preview; `follow` takes `ui.theme`'s side. Native controls follow `ui.theme` only. Any other value reads as `follow` with one warning. While it is missing or `follow`, a `ui.themeFile` line names the theme file, and the next save replaces that line with `ui.editorColors` | `follow` | Tools > Preferences, Editor |
| `ui.toolbar` | `true`, `false`: the windows show the main toolbar | `true` | View > Toolbar |
| `ui.rightMargin` | `true`, `false`: the editors show the right margin line | `false` | View > Right Margin |
| `ui.indentGuides` | `true`, `false`: the editors show indent guides | `false` | View > Indent Guides |
| `ui.lineChanges` | `true`, `false`: the editors mark the lines changed since the last load or save | `false` | View > Modified Line Marks |
| `ui.memoryIndicator` | `true`, `false`: the status bars show the memory indicator | `false` | View > Memory Indicator; Tools > Preferences, View |
| `ui.wordWrap` | `true`, `false`: a window without a session entry starts with word wrap on | `false` | View > Word Wrap; Tools > Preferences, View |
| `ui.whitespace` | `true`, `false`: such a window starts with the whitespace marks | `false` | View > Show Whitespace; Tools > Preferences, View |
| `ui.lineEndings` | `true`, `false`: such a window starts with the line-ending marks | `false` | View > Show Line Endings; Tools > Preferences, View |
| `ui.overview` | `true`, `false`: such a window starts with the overview strip | `true` | View > Overview Strip; Tools > Preferences, View |
| `ui.lineNumbers` | `true`, `false`: such a window starts with line numbers | `true` | View > Line Numbers; Tools > Preferences, View |
| `ui.workspaceBrowser` | `true`, `false`: such a window starts with the workspace browser shown | `false` | View > Workspace Browser; Tools > Preferences, View |
| `ui.lastDateFormat` | The pattern Insert Date and Time used last, selected first in its list | none | Edit > Insert Date and Time |
| `window.x`, `.y`, `.width`, `.height`, `.maximized` | The normal position, client size and maximized state at the last exit | none | Exit, unless a `--window-*` option or `-m` was given |
| `log.debugFile` | `true`, `false`: the debug log file is attached at start | `false` | Help > Debug Log File |
| `help.tipsOnStartup` | `true`, `false`: Tip of the Day opens at start, after the session restore and the recovery prompt | `false` | Help > Tip of the Day, Show tips on startup |
| `help.nextTip` | 0 to 9999: the tip shown next; past the last tip it wraps | 0 | Each tip shown |
| `editor.completionChars` | 0 to 20: word chars typed before completion opens, 0 for never | 0 | Tools > Preferences, Editor |
| `editor.autoClose` | `true`, `false`: typing an opener inserts its closer | `true` | Tools > Preferences, Editor |
| `editor.maxHighlightLineChars` | 1,000 to 1,000,000: a longer line shows without syntax colors | 10000 | Tools > Preferences, Editor |
| `editor.tabWidth` | 1 to 16: columns per tab stop of a document without saved settings | 4 | Tools > Preferences, Editor |
| `editor.insertSpaces` | `true`, `false`: such a document indents with spaces | `false` | Tools > Preferences, Editor |
| `editor.rightMargin` | 1 to 1000: the column the right margin line sits left of, and the widest line Wrap Paragraph makes | 80 | Tools > Preferences, Editor |
| `editor.dateFormat` | A `DateTimeFormatter` pattern, at most 100 chars: Insert Date and Time's first format | `yyyy-MM-dd HH:mm` | Tools > Preferences, Editor |
| `editor.editorConfig` | `true`, `false`: an opened file takes the settings of its `.editorconfig` files | `true` | Tools > Preferences, Editor |
| `editor.modelines` | `true`, `false`: an opened file takes the settings of its vim and Emacs modelines | `true` | Tools > Preferences, Editor |
| `editor.newLineEnding` | `lf`, `crlf`, `platform` (CRLF on Windows, else LF): Enter's line ending in a new document, a missing file opened by path, and a loaded text without line breaks, until per-file settings say otherwise | `lf` | Tools > Preferences, Editor |
| `editor.newEncoding` | A charset of the Encoding menu, spelled as there (`UTF-8`, `windows-1252`, ...): the encoding of a new document, a missing file opened by path, and a file of 0 bytes, unless forced or set by `.editorconfig` | `UTF-8` | Tools > Preferences, Editor |
| `editor.fontSize` | 0 for the platform's default, or 4 to 72 points | 0 | View > Zoom In, Zoom Out and Reset Zoom; Tools > Preferences, Appearance |
| `editor.fontFamily` | A monospace family, empty for the platform's default. A family that is not installed or measures proportional gives way to the default with one warning and stays saved | empty | Tools > Preferences, Appearance |
| `file.pollSeconds` | 1 to 3600: seconds between checks of every open file | 5 | Tools > Preferences, Files |
| `file.autoReloadClean` | `true`, `false`: a changed file reloads a buffer without unsaved changes | `true` | Tools > Preferences, Files |
| `recovery.intervalSeconds` | 5 to 600: seconds between writes of each changed unsaved buffer's recovery file | 30 | Tools > Preferences, Files |
| `recovery.keepDays` | 1 to 365: days a recovery file left by a crash is kept | 30 | Tools > Preferences, Files |
| `tabs.closeWindowWithLastTab` | `true`, `false`: closing a window's last tab closes the window, and the last window quits; off, an untitled document takes the tab's place | `false` | Tools > Preferences, Files |
| `session.snapshot` | `true`, `false`: a quit asks nothing and the next start restores unsaved and untitled buffers | `false` | Tools > Preferences, Files |
| `search.maxLineChars` | 10,000 to 100,000,000: HyperSearch and replace skip and count a longer line | 1000000 | Tools > Preferences, Search and Workspace |
| `replace.backupDays` | 1 to 365: days a replace run's backups are kept | 7 | Tools > Preferences, Search and Workspace |
| `workspace.excludedDirectories` | Directory names separated by `;`: the browser, Quick Open's index and directory searches skip them | `.git;.svn;.hg;target;node_modules;build` | Tools > Preferences, Search and Workspace |
| `shortcut.<COMMAND>` | Keys separated by spaces, such as `Mod+Alt+K F7`; `Mod` is Ctrl, or Command on macOS, and `Control` is the macOS Control key; empty for no key | The command's default keys | Tools > Preferences, Keys |
| `search.regex`, `.caseSensitive`, `.wholeWord`, `.hiddenFiles` | The find bar's and HyperSearch's toggles | `false` | The find bar, HyperSearch |
| `search.history.text.N`, `.directory.N`, `.mask.N`, `.replace.N` | Search texts of the find bar and HyperSearch, HyperSearch's directories and include masks, the find bar's replacements; newest first, 20 each | none | The find bar, HyperSearch |
| `syntax.recent` | The last five grammars chosen, ids separated by `;` | none | View > Syntax |
| `dialog.<name>.width`, `.height` | A resizable dialog's last size | none | Closing the dialog |
| `workspace.favorite.N` | The workspace browser's favorite folders in the order added, 20 at most | none | The browser's Favorites menu |
| `tabs.closeShowsLastUsed` | `true`, `false`: closing the shown tab shows the open tab used most recently; off, the tab after it, else the one before | `false` | Tools > Preferences, Files |

A value out of range falls back to its default with one warning naming the key.
