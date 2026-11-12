# qcaret-ui-swt

The SWT views: the main window with its tabs, menus and toolbar, the editor
(`ui.swt.editor`), the dialogs and docks, the theme service and the UI
dispatcher. It depends on `qcaret-app`, `qcaret-core` and the build host's SWT
fragment. It uses public SWT API except in `ToolIcons` and the `WindowStacking`
classes, which reach SWT internals by reflection.

---

## Map

| Package | Contents | Detail |
|---|---|---|
| `ui.swt` | `MainWindow`, `MainMenuBar`, `MainToolBar`, `EditorMenu`, `TabMenu`, `TabStripButtons`, `TabList`, `AppMenu`, `WindowStacking`, status bar, `ProgressRow`, file drop | [Main window](docs/main-window.md) |
| `ui.swt` | Prompts, `ShortcutsWindow`, `PreferencesDialog`, `RecoveryDialog`, `CommandPaletteWindow`, `QuickOpenWindow`, `WorkspaceDock`, `BrowserSideWidth`, `BrowseBar`, `BrowserRowTip`, `BrowserDrag`, `FileNameDialog`, `DeleteDialog`, `StatisticsDialog`, `HashTextDialog`, `ChecksumDialog`, `DateFormatChooser`, `FindBar`, `SearchDialog`, `BookmarksDock`, `ChangeDialog`, `DiffWindow`, `GuideWindow`, `TipDialog` | [Dialogs and docks](docs/dialogs-and-docks.md) |
| `ui.swt.search` | `ResultsDock`, `ResultRows`, `ReplaceDialog`, `ReportWindow`, `RunChooser` | [Dialogs and docks](docs/dialogs-and-docks.md#results-dock) |
| `ui.swt.editor` | `EditorPane`, `ContentAdapter`, `EditorGestures`, `EditorLayout`, `CaretInk`, `CompletionPopup`, `OverviewStrip`, `MarginPainter`, `IndentGuidePainter`, `LineChangePainter` | [Editor pane](docs/editor.md) |
| `ui.swt.platform` | `PlatformTraits` and its win32, cocoa, GTK 3 and GTK 4 classes | [Main window](docs/main-window.md#platform-traits) |
| `ui.swt` | `PrintJob`, `PdfPrinter` and the printer calls | [Printing](docs/printing.md) |
| `ui.swt` | `SwtThemeService`, `CocoaChrome`, `DialogSurfaces`, tab colors, `ToolIcons`, `MenuIcons`, the tool icon set | [Theme and icons](docs/theme-and-icons.md) |

## UI thread

Every class here runs on the UI thread, except `PrintJob`'s printing on its
worker. `SwtUiDispatcher` is the only class that posts to the Display
(`asyncExec`, `timerExec`); no code calls `Display.syncExec`, and no call in
`ui.swt` blocks. Submissions after the Display is disposed are dropped, and a
timer canceled or posted after disposal does nothing.

- The views read menu and status state from `CommandStates`, never from
  controller or `Document` getters, and send every edit through a controller.
- A dialog opens without a nested event loop; the four calls that run one are
  listed in [Dialogs and docks](docs/dialogs-and-docks.md#nested-event-loops).
- The model is the only authority for the editor's text and selections; the
  `StyledText` holds the display rows and the primary selection.

## Testing

Tests tagged `swt` open real windows and run under `HangWatchdog`. The harness,
coverage by class and the IBus test are in [TESTING.md](TESTING.md).
