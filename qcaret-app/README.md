# qcaret-app

The application layer without a UI toolkit: command line, commands and menus,
the controllers, the display projection, view contracts, settings, sessions and
recovery, themes, UI strings, About facts and the log ring. It depends on
`qcaret-core` and slf4j-api only, never on SWT, so every class is tested
without a display. Another toolkit reuses all of it and supplies `MainView`
(with its tabs and editors), `UiDispatcher` and `ThemeService`; `qcaret-ui-swt`
is that toolkit and `qcaret-launcher` composes the two.

---

## Map

| Package | Contents | Detail |
|---|---|---|
| `app.cli` | `CommandLineParser`, `CommandLine`, `StartupRequest`, `FileRequest`, `Version` | [Views and commands](docs/views-and-commands.md) |
| `app.view` | `UiDispatcher`, `MainView` and the views it extends, input models and row types, `StatusFields`, `WindowGeometry` | [Views and commands](docs/views-and-commands.md) |
| `app.command`, `app.controller` | `CommandId`, `CommandGroup`, `Keymap`, `KeyStroke`, `MenuTable`, `Fuzzy`; `CommandStates`, `CommandPalette` | [Views and commands](docs/views-and-commands.md) |
| `app.ipc` | Single-instance lock, endpoint and handoff protocol | [Single instance](docs/single-instance.md) |
| `app.controller` | `WindowSet`, `ApplicationController`, quit, tab moves | [Windows and tabs](docs/windows-and-tabs.md) |
| `app.controller`, `app.print` | `DocumentController`, `OpenDocument`, `DocumentOpener`: open, load, save, encodings, split views; printing | [Documents](docs/documents.md) |
| `app.controller` | `ExternalChanges`, reload, change prompts, `CompareController` | [External changes](docs/external-changes.md) |
| `app.controller`, `app.search` | Find bar, HyperSearch, result sets, replace across files | [Search](docs/search.md) |
| `app.controller` | Workspace folder and browser, file index, Quick Open, navigation history, Recent Files | [Workspace and navigation](docs/workspace-and-navigation.md) |
| `app.controller` | `EditorController`: edits, carets, folding, bookmarks, completion; overview strip; Bookmarks list | [Editor](docs/editor.md) |
| `app.editor` | `DisplayProjection`, `FoldLayer`, `HighlightComposer`, `TokenCache`, `SmartIndent`, `ColumnMetrics`, `Bookmarks` | [Display projection](docs/display-projection.md) |
| `app.controller` | `GrammarLibrary`, grammar detection, Syntax menu, `SyntaxHighlighter` | [Highlighting](docs/highlighting.md) |
| `app.config`, `app.controller` | Settings directory, `qcaret.conf`, `Settings`, key binding overrides, the settings keys | [Settings](docs/settings.md) |
| `app.session`, `app.recovery` | Session file, per-file state, recovery files, quit snapshot | [Sessions and recovery](docs/sessions-and-recovery.md) |
| `app.about`, `app.i18n`, `app.log`, `app.theme` | Help commands, the user guide build, `UiText`, About, the log ring; themes, theme files, font discovery | [Help and themes](docs/help-and-themes.md) |

## Controller threading

Every controller is confined to the UI thread and never blocks it.
`ArchitectureTest` rejects `Future.get`, `Thread.join`, `Thread.sleep` and
`CountDownLatch.await` in `app.controller` and `app.command`. Controllers read
no system property; platform facts come from the caller.

A blocking call in `app.controller` (the list is `BLOCKING` in
`ArchitectureTest`: `DocumentFiles` loads and saves, `SafeSave`, `Files`,
`FileChannel`, `Path.toRealPath`, `Charset.availableCharsets`, `AtomicFiles`,
`SafePaths`, `ConfigStore.load` and `save`, and others) is allowed only:

- in a method annotated `@WorkerThread`;
- in `run` of an anonymous `Runnable` that its creating method passes to
  `Workers`, directly or through a helper of the same class that takes a
  `Runnable`.

A creating method that also creates another anonymous `Runnable` or takes a
method reference does not qualify; annotate that `run` instead. A
`@WorkerThread` method is called only from such code, so annotating a UI-thread
method fails at its caller.

Controllers run work on these `Workers` pools:

| Thread prefix | Work |
|---|---|
| `qcaret-cpu-` | Find matching, HyperSearch document matching, line transforms and conversions, rewraps, fold regions and Fold All, highlighting, word indexes, overview marks, the Bookmarks list, comparisons, large Quick Open matches |
| `qcaret-io-` | Path resolution, loads, saves, reloads, directory listings, stamp checks, file replaces, recovery writes, theme files, font discovery |
| `qcaret-search-` | Folder walks of HyperSearch, the workspace file index |
| `qcaret-watch-` | External change checks |
| `qcaret-lifecycle-` | Settings and session writes, the end of a quit, Help commands: the guide copy, About, licenses, the debug log file |
| `qcaret-ipc-` | The handoff server's accept and connection tasks |
| `qcaret-print-` | One print job, on a platform thread |

Every worker result carries its generation or the document revision it read,
and is dropped on the UI thread when either is stale. Views read menu and
status state from `CommandStates`, never from controller getters.

## Testing

Tests run without a display, over `FakeView` and `ManualUiDispatcher` with real
workers and files. The harness and coverage by class are in
[TESTING.md](TESTING.md).
