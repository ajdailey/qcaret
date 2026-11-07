# Settings

The settings directory, `qcaret.conf`, the running `Settings` copy and key
binding overrides, in `app.config` and `app.controller` of
[qcaret-app](../README.md). The directory modes and the files beside
`qcaret.conf` are in the root `README.md`.

## Settings directory

`SettingsLocation` picks the settings directory: `--settings <dir>` (explicit),
the installation directory `qcaret.home` when it holds `qcaret.conf`
(portable), else the per-user directory of the OS. The bootstrap applies the
same order to the SWT native cache.

## Preferences record

`Preferences` holds `ui.theme`, `window.*`, `log.debugFile` (attaches the debug
log file at the next start), `Options` (the Preferences dialog's settings),
`UiState` (search toggles, histories, recent grammars, dialog sizes, the
toolbar choice), `ShortcutOverrides` and `Appearance` (`ui.themeFile`,
`editor.fontFamily`). `PreferenceKeys` maps them to and from `qcaret.conf`. A
new setting is a record component and its lines in `PreferenceKeys`, plus a
`PreferencesForm` field when the dialog shows it.

## Settings file

`ConfigStore` has two blocking calls, serialized by a lock. Both run off the UI
thread, apart from the first load before the Display:

| Call | Effect |
|---|---|
| `load()` | Reads the file |
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
`current()`; no toggle or history has a per-window field. `set` replaces the
copy, calls the listeners on the UI thread and queues the change for a
`qcaret-lifecycle-` worker. Workers drain the queue in order under `saveLock`,
which the UI thread never takes, as one `save` from the first change's `before`
to the last change's `after`. `ApplicationController.apply` is the listener
that brings each running window in line, whatever the change's source.

`refresh` drains the queue, loads the file on a worker and takes up what
another process changed; a change made meanwhile survives through `merge`. A
failed save or a read-only directory keeps the running settings.

## Preferences dialog

`PreferencesController` runs `refresh` before the dialog opens, so the dialog
shows the file's values, and hands checked fields to `set`. A failed save shows
a notice, and the settings stay applied for the run. Every setting applies to
the running windows; none needs a restart.

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
| `ui.toolbar` | `true`, `false`: the windows show the main toolbar | `true` | View > Toolbar |
| `window.x`, `.y`, `.width`, `.height`, `.maximized` | The normal position, client size and maximized state at the last exit | none | Exit, unless a `--window-*` option or `-m` was given |
| `log.debugFile` | `true`, `false`: the debug log file is attached at start | `false` | Help > Debug Log File |
| `editor.completionChars` | 0 to 20: word chars typed before completion opens, 0 for never | 0 | Edit > Preferences, Editor |
| `editor.autoClose` | `true`, `false`: typing an opener inserts its closer | `true` | Edit > Preferences, Editor |
| `editor.maxHighlightLineChars` | 1,000 to 1,000,000: a longer line shows without syntax colors | 10000 | Edit > Preferences, Editor |
| `editor.tabWidth` | 1 to 16: columns per tab stop of a document without saved settings | 4 | Edit > Preferences, Editor |
| `editor.insertSpaces` | `true`, `false`: such a document indents with spaces | `false` | Edit > Preferences, Editor |
| `editor.fontSize` | 0 for the platform's default, or 4 to 72 points | 0 | View > Zoom, Edit > Preferences, Appearance |
| `editor.fontFamily` | A monospace family, empty for the platform's default. A family that is not installed or measures proportional gives way to the default with one warning and stays saved | empty | Edit > Preferences, Appearance |
| `ui.themeFile` | A file in `themes/` in the settings directory, without `.theme`; empty for the built-in colors | empty | Edit > Preferences, Appearance |
| `file.pollSeconds` | 1 to 3600: seconds between checks of every open file | 5 | Edit > Preferences, Files |
| `file.autoReloadClean` | `true`, `false`: a changed file reloads a buffer without unsaved changes | `true` | Edit > Preferences, Files |
| `recovery.intervalSeconds` | 5 to 600: seconds between writes of each changed unsaved buffer's recovery file | 30 | Edit > Preferences, Files |
| `recovery.keepDays` | 1 to 365: days a recovery file left by a crash is kept | 30 | Edit > Preferences, Files |
| `session.snapshot` | `true`, `false`: a quit asks nothing and the next start restores unsaved and untitled buffers | `false` | Edit > Preferences, Files |
| `search.maxLineChars` | 10,000 to 100,000,000: HyperSearch and replace skip and count a longer line | 1000000 | Edit > Preferences, Search and Workspace |
| `replace.backupDays` | 1 to 365: days a replace run's backups are kept | 7 | Edit > Preferences, Search and Workspace |
| `workspace.excludedDirectories` | Directory names separated by `;`: the browser, Quick Open's index and directory searches skip them | `.git;.svn;.hg;target;node_modules;build` | Edit > Preferences, Search and Workspace |
| `shortcut.<COMMAND>` | Keys separated by spaces, such as `Mod+Alt+K F7`; `Mod` is Ctrl, or Command on macOS, and `Control` is the macOS Control key; empty for no key | The command's default keys | Edit > Preferences, Keys |
| `search.regex`, `.caseSensitive`, `.wholeWord`, `.hiddenFiles` | The find bar's and HyperSearch's toggles | `false` | The find bar, HyperSearch |
| `search.history.text.N`, `.directory.N`, `.mask.N`, `.replace.N` | Search texts of the find bar and HyperSearch, HyperSearch's directories and include masks, the find bar's replacements; newest first, 20 each | none | The find bar, HyperSearch |
| `syntax.recent` | The last five grammars chosen, ids separated by `;` | none | View > Syntax |
| `dialog.<name>.width`, `.height` | A resizable dialog's last size | none | Closing the dialog |

A value out of range falls back to its default with one warning naming the key.
