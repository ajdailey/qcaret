# qcaret-ui-swt

The SWT views: the main window with its tabs, menus and toolbar, the editor
(`ui.swt.editor`), the dialogs and docks, the theme service and the UI
dispatcher. It depends on `qcaret-app`, `qcaret-core` and the build host's SWT
fragment. It uses public SWT API except in `Gtk4ToolItemSizing`, the `WindowStacking`
classes, `CocoaPrintJobNaming`, `Gtk3NativeNames`, `Win32BrowserBehavior` and
`Win32ListBox` (the combo list and list selection), all in `ui.swt.platform`,
which reach SWT internals by reflection, and `Win32HeaderLines`, which sets the
Display data key `org.eclipse.swt.internal.win32.Table.headerLineColor`
([Dialog colors](docs/theme-and-icons.md#dialog-colors)).

---

## Map

| Package | Contents | Detail |
|---|---|---|
| `ui.swt` | `MainWindow`, `TabStrip`, `DeferredDisposal`, `EditorTab`, `MainMenuBar`, `EncodingCascade`, `RecentMenus`, `MainToolBar`, `EditorMenu`, `TabMenu`, `StatusMenus`, `StatusUpdates`, `StatusText`, `TabStripButtons`, `TabList`, `AppMenu`, status bar, `MemoryGauge`, `WindowBusyCursor`, `ProgressRow`, `ToolbarOverflow`, `WindowViews`, `MainWindowSlot`, file drop | [Main window](docs/main-window.md) |
| `ui.swt` | `WindowDialogs`, prompts, `ShortcutsWindow`, `TableFit`, `PreferencesDialog`, `PreferencesViewTab`, `WorkspaceDefaultRow`, `RecoveryDialog`, `CommandPaletteWindow`, `QuickOpenWindow`, `WorkspaceDock`, `BrowserTreeSync`, `BrowserColumns`, `BrowserSideWidth`, `BrowseBar`, `BrowserRowTip`, `BrowserDrag`, `FileNameDialog`, `DeleteDialog`, `StatisticsDialog`, `HashTextDialog`, `ChecksumDialog`, `DateFormatChooser`, `NumberSequenceDialog`, `WindowMacroDialogs`, `RunMacroDialog`, `SaveMacroDialog`, `ManageMacrosDialog`, `KeyField`, `KeyCapture`, `SavedMacroItems`, `FindBar`, `HistoryField`, `PopupSelection`, `SearchDialog`, `BookmarksDock`, `BookmarksMenu`, `ChangeDialog`, `DiffWindow`, `GuideWindow`, `TipDialog`, `OutlineDock`, `DockBarLayout` | [Dialogs and docks](docs/dialogs-and-docks.md) |
| `ui.swt.search` | `ResultsDock`, `ResultsMenu`, `ResultRows`, `ReplaceDialog`, `ReportWindow`, `RunChooser` | [Dialogs and docks](docs/dialogs-and-docks.md#results-dock) |
| `ui.swt.editor` | `EditorPane`, `ContentAdapter`, `EditorGestures`, `EditorLayout`, `CaretInk`, `CompletionPopup`, `PaneHighlights`, `RedrawHold`, `PaintWait`, `PanePainter`, `OverviewStrip`, `MarginPainter`, `IndentGuidePainter`, `LineChangePainter` | [Editor pane](docs/editor.md) |
| `ui.swt.platform` | `PlatformTraits` and its win32, cocoa, GTK 3 and GTK 4 classes; the seams `ChromeColors`, `DialogColors`, `DialogTabs`, `ThemeFollow`, `MenuBehavior`, `ToolItemSizing`, `KeyBehavior`, `BrowserBehavior`, `GuideDisplay`, `PrintSupport`, `FullScreen`, `WindowStacking`, `WindowPlacement`, `NativeNames`, `AccessibleLabels` and `LaunchDocuments` with their per-platform classes | [Main window](docs/main-window.md#platform-traits) |
| `ui.swt`, `ui.swt.markdown` | `PrintJob`, `PdfPrinter`, the printer calls; `MarkdownPrinting`, `PrintLines`, `PrintMeasurer` | [Printing](docs/printing.md) |
| `ui.swt.markdown`, `ui.swt` | `MarkdownPane`, `MarkdownStyler`, `MarkdownPainter`, `TableColumns`, `PaneImages`, `SwtImageDecoder`, `PreviewSlot`, `MarkdownSwitch`, `BrowserPane`, `BrowserPreview`, `MarkdownToolItems` | [Markdown preview](docs/markdown-preview.md) |
| `ui.swt.hex`, `ui.swt` | `HexPane`, `HexCanvas`, `HexPainter`, `HexGestures`, `HexGrid`, `HexFindBar`; `HexSlot`, `HexWindow`, `WindowHexViews`, `BinaryDialog` | [Hex view](docs/hex-view.md) |
| `ui.swt`, `ui.swt.platform` | `SwtThemeService`, `CocoaChrome`, `ChromeColors`, `DialogColors`, `DialogTabs`, tab colors, `ToolIcons`, `MenuIcons`, the tool icon set | [Theme and icons](docs/theme-and-icons.md) |

## UI thread

Every class here runs on the UI thread, except `SwtImageDecoder` on the
Markdown controller's worker and `PrintJob`, `MarkdownPrinting` and
`PrintMeasurer` on the print worker. `SwtUiDispatcher` is the only class that posts to the Display
(`asyncExec`, `timerExec`, `execute`); no code calls `Display.syncExec` or
`syncCall`, and no call in
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
