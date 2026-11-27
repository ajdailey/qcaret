package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.view.BinaryChoice;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.BusyCounter;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.ChecksumDisplay;
import io.github.ajdailey.qcaret.app.view.ChecksumInput;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.HashTextDisplay;
import io.github.ajdailey.qcaret.app.view.HashTextInput;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexMode;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.MacroManagerDisplay;
import io.github.ajdailey.qcaret.app.view.MacroManagerInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.MemoryFields;
import io.github.ajdailey.qcaret.app.view.OutlineInput;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.RunMacroForm;
import io.github.ajdailey.qcaret.app.view.SaveMacroForm;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SequenceForm;
import io.github.ajdailey.qcaret.app.view.StatisticsDisplay;
import io.github.ajdailey.qcaret.app.view.StatisticsInput;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.TipDisplay;
import io.github.ajdailey.qcaret.app.view.TipInput;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * A window's {@link MainView} for its controllers: forwards every call to the current {@link
 * MainWindow}, so the controllers, their workers and their state stay when {@link WindowViews}
 * replaces the window with a new one. The slot keeps the busy count, and the dock, editor display
 * and status settings the controllers pushed, and pushes them again to a replacement ({@link
 * #replace}). Confined to the UI thread, except {@link #setActive} and {@link #setIdle}.
 */
public final class MainWindowSlot implements MainView {
  private MainWindow window;
  private MainView view;
  private final BusyCounter busy;
  private WorkspaceInput workspace;
  private boolean workspaceShown;
  private ResultsInput results;
  private boolean resultsShown;
  private BookmarksInput bookmarks;
  private boolean bookmarksShown;
  private OutlineInput outline;
  private boolean outlineShown;
  private int outlineIndex = -1;
  private Boolean toolbarShown;
  private Integer fontSize;
  private String fontFamily;
  private Boolean lineNumbersShown;
  private Integer rightMargin;
  private Boolean indentGuides;
  private Boolean lineChangesShown;
  private Boolean memoryShown;
  private MemoryFields memory;
  private boolean warningMarker;
  private String macroStatus = "";
  private Boolean onTop;
  private Progress print;
  private Progress load;

  /** A progress row's last call. */
  private record Progress(String text, int percent, Runnable cancel) {}

  /**
   * Creates the slot over a window.
   *
   * @param window the window the slot forwards to first
   */
  public MainWindowSlot(MainWindow window) {
    this(window, window);
  }

  /**
   * Creates the slot over a window with the calls forwarded to another view.
   *
   * @param window the window
   * @param view receives the forwarded calls
   */
  MainWindowSlot(MainWindow window, MainView view) {
    this.window = window;
    this.view = view;
    this.busy =
        new BusyCounter(
            new SwtUiDispatcher(window.shell().getDisplay()),
            new BusyCounter.Indicator() {
              @Override
              public void busyChanged(boolean on) {
                MainWindowSlot.this.window.busyIndicator().busyChanged(on);
              }
            });
  }

  /**
   * Returns the window the slot forwards to.
   *
   * @return the current window
   */
  public MainWindow window() {
    return window;
  }

  /**
   * Forwards to a new window, attached to the same controller, and pushes the settings, docks and
   * busy state the old one showed.
   *
   * @param next the replacement
   */
  void replace(MainWindow next) {
    window = next;
    view = next;
    if (toolbarShown != null) {
      next.setToolbarShown(toolbarShown);
    }
    if (fontSize != null) {
      next.setFontSize(fontSize);
    }
    if (fontFamily != null) {
      next.setFontFamily(fontFamily);
    }
    if (lineNumbersShown != null) {
      next.setLineNumbersShown(lineNumbersShown);
    }
    if (rightMargin != null) {
      next.setRightMargin(rightMargin);
    }
    if (indentGuides != null) {
      next.setIndentGuides(indentGuides);
    }
    if (lineChangesShown != null) {
      next.setLineChangesShown(lineChangesShown);
    }
    if (memoryShown != null) {
      next.setMemoryShown(memoryShown);
    }
    if (memory != null) {
      next.showMemory(memory);
    }
    next.showWarningMarker(warningMarker);
    next.showMacroStatus(macroStatus);
    if (workspace != null) {
      next.showWorkspace(workspace, workspaceShown);
      next.workspaceChanged(null);
    }
    if (results != null) {
      next.showResults(results, resultsShown);
      next.resultsChanged(true);
    }
    if (bookmarks != null) {
      next.showBookmarks(bookmarks, bookmarksShown);
      next.bookmarksChanged();
    }
    if (outline != null) {
      next.showOutline(outline, outlineShown);
      next.outlineChanged();
      if (outlineIndex >= 0) {
        next.outlineSelected(outlineIndex);
      }
    }
    if (print != null) {
      next.showPrintProgress(print.text(), print.percent(), print.cancel());
    }
    if (load != null) {
      next.showLoadProgress(load.text(), load.percent(), load.cancel());
    }
    if (onTop != null) {
      next.setOnTop(onTop);
    }
    if (busy.active() > 0) {
      next.busyIndicator().busyChanged(true);
    }
  }

  /**
   * Reports whether a dialog that a rebuild of the window would dispose is open: a modal dialog or
   * prompt, or a native dialog's nested event loop ({@link NativeDialogs}).
   *
   * @return true while one is open
   */
  boolean holdsDialog() {
    return NativeDialogs.openOver(window.shell()) || window.promptShown();
  }

  /**
   * Returns the operation count behind the busy cursor.
   *
   * @return the counter
   */
  BusyCounter busy() {
    return busy;
  }

  @Override
  public void setActive() {
    busy.setActive();
  }

  @Override
  public void setIdle() {
    busy.setIdle();
  }

  @Override
  public void chooseFilesToOpen(Path directory, Consumer<List<Path>> chosen) {
    view.chooseFilesToOpen(directory, chosen);
  }

  @Override
  public void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen) {
    view.chooseSaveTarget(directory, name, chosen);
  }

  @Override
  public void chooseFolder(Path start, Consumer<Path> chosen) {
    view.chooseFolder(start, chosen);
  }

  @Override
  public void choosePrinter(String document, Consumer<PrintTarget> chosen) {
    view.choosePrinter(document, chosen);
  }

  @Override
  public void showWorkspace(WorkspaceInput input, boolean shown) {
    workspace = input;
    workspaceShown = shown;
    view.showWorkspace(input, shown);
  }

  @Override
  public void showResults(ResultsInput input, boolean shown) {
    results = input;
    resultsShown = shown;
    view.showResults(input, shown);
  }

  @Override
  public void showBookmarks(BookmarksInput input, boolean shown) {
    bookmarks = input;
    bookmarksShown = shown;
    view.showBookmarks(input, shown);
  }

  @Override
  public void showOutline(OutlineInput input, boolean shown) {
    outline = input;
    outlineShown = shown;
    view.showOutline(input, shown);
  }

  @Override
  public void outlineSelected(int index) {
    outlineIndex = index;
    view.outlineSelected(index);
  }

  @Override
  public void setToolbarShown(boolean shown) {
    toolbarShown = shown;
    view.setToolbarShown(shown);
  }

  @Override
  public void setFontSize(int points) {
    fontSize = points;
    view.setFontSize(points);
  }

  @Override
  public void setFontFamily(String family) {
    fontFamily = family;
    view.setFontFamily(family);
  }

  @Override
  public void setLineNumbersShown(boolean shown) {
    lineNumbersShown = shown;
    view.setLineNumbersShown(shown);
  }

  @Override
  public void setRightMargin(int column) {
    rightMargin = column;
    view.setRightMargin(column);
  }

  @Override
  public void setIndentGuides(boolean shown) {
    indentGuides = shown;
    view.setIndentGuides(shown);
  }

  @Override
  public void setLineChangesShown(boolean shown) {
    lineChangesShown = shown;
    view.setLineChangesShown(shown);
  }

  @Override
  public void setMemoryShown(boolean shown) {
    memoryShown = shown;
    view.setMemoryShown(shown);
  }

  @Override
  public void showMemory(MemoryFields fields) {
    memory = fields;
    view.showMemory(fields);
  }

  @Override
  public void showWarningMarker(boolean shown) {
    warningMarker = shown;
    view.showWarningMarker(shown);
  }

  @Override
  public void setOnTop(boolean value) {
    onTop = value;
    view.setOnTop(value);
  }

  @Override
  public void showPrintProgress(String text, int percent, Runnable cancel) {
    print = new Progress(text, percent, cancel);
    view.showPrintProgress(text, percent, cancel);
  }

  @Override
  public void hidePrintProgress() {
    print = null;
    view.hidePrintProgress();
  }

  @Override
  public void showLoadProgress(String text, int percent, Runnable cancel) {
    load = new Progress(text, percent, cancel);
    view.showLoadProgress(text, percent, cancel);
  }

  @Override
  public void hideLoadProgress() {
    load = null;
    view.hideLoadProgress();
  }

  // Forwarded as they are.

  @Override
  public void hide() {
    view.hide();
  }

  @Override
  public void toFront() {
    view.toFront();
  }

  @Override
  public void dispose() {
    view.dispose();
  }

  @Override
  public void showInfo(String title, String message) {
    view.showInfo(title, message);
  }

  @Override
  public void showText(String title, String text) {
    view.showText(title, text);
  }

  @Override
  public void showAbout(AboutInfo info) {
    view.showAbout(info);
  }

  @Override
  public String toolkitDescription() {
    return view.toolkitDescription();
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    return view.windowBounds();
  }

  @Override
  public boolean windowMaximized() {
    return view.windowMaximized();
  }

  @Override
  public void showNotice(String message) {
    view.showNotice(message);
  }

  @Override
  public void showLog(String text) {
    view.showLog(text);
  }

  @Override
  public boolean launch(Path target) {
    return view.launch(target);
  }

  @Override
  public boolean launchUrl(String url) {
    return view.launchUrl(url);
  }

  @Override
  public boolean showGuide(Path file) {
    return view.showGuide(file);
  }

  @Override
  public StatisticsDisplay showStatistics(StatisticsInput input) {
    return view.showStatistics(input);
  }

  @Override
  public HashTextDisplay showHashText(String title, String note, HashTextInput input) {
    return view.showHashText(title, note, input);
  }

  @Override
  public ChecksumDisplay showChecksums(
      String title, String note, String chooseLabel, ChecksumInput input) {
    return view.showChecksums(title, note, chooseLabel, input);
  }

  @Override
  public void chooseDateFormat(List<String> rows, int selected, Consumer<Integer> chosen) {
    view.chooseDateFormat(rows, selected, chosen);
  }

  @Override
  public int defaultFontSize() {
    return view.defaultFontSize();
  }

  @Override
  public void showKeyboardShortcuts(Keymap keymap) {
    view.showKeyboardShortcuts(keymap);
  }

  @Override
  public void showCommandPalette(PaletteInput input) {
    view.showCommandPalette(input);
  }

  @Override
  public void showPreferences(PreferencesInput input) {
    view.showPreferences(input);
  }

  @Override
  public void setKeymap(Keymap keymap) {
    view.setKeymap(keymap);
  }

  @Override
  public boolean onTopSupported() {
    return view.onTopSupported();
  }

  @Override
  public boolean minimizeSupported() {
    return view.minimizeSupported();
  }

  @Override
  public void minimize() {
    view.minimize();
  }

  @Override
  public void zoom() {
    view.zoom();
  }

  @Override
  public void askNumberSequence(
      SequenceForm initial, Predicate<SequenceForm> valid, Consumer<SequenceForm> answer) {
    view.askNumberSequence(initial, valid, answer);
  }

  @Override
  public boolean fullScreen() {
    return view.fullScreen();
  }

  @Override
  public void setFullScreen(boolean on) {
    view.setFullScreen(on);
  }

  @Override
  public TipDisplay showTips(TipInput input) {
    return view.showTips(input);
  }

  @Override
  public boolean promptShown() {
    return view.promptShown();
  }

  @Override
  public Runnable askUnfinishedCopies(List<String> targets, Runnable quit, Runnable wait) {
    return view.askUnfinishedCopies(targets, quit, wait);
  }

  @Override
  public String previewBrowserUnavailable() {
    return view.previewBrowserUnavailable();
  }

  @Override
  public boolean previewFocused() {
    return view.previewFocused();
  }

  @Override
  public void askOpenUrl(String url, Runnable open) {
    view.askOpenUrl(url, open);
  }

  @Override
  public void setText(String text) {
    view.setText(text);
  }

  @Override
  public String clipboardText() {
    return view.clipboardText();
  }

  @Override
  public TabView addTab(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      TabInput tab,
      int index) {
    return view.addTab(projection, selections, editor, tab, index);
  }

  @Override
  public void documentStateChanged() {
    view.documentStateChanged();
  }

  @Override
  public void editStateChanged() {
    view.editStateChanged();
  }

  @Override
  public void showMacroStatus(String text) {
    macroStatus = text;
    view.showMacroStatus(text);
  }

  @Override
  public void askRunMacro(
      RunMacroForm initial, Function<RunMacroForm, String> problem, Consumer<RunMacroForm> answer) {
    view.askRunMacro(initial, problem, answer);
  }

  @Override
  public void askSaveMacro(
      SaveMacroForm initial,
      Function<SaveMacroForm, String> problem,
      Consumer<SaveMacroForm> answer) {
    view.askSaveMacro(initial, problem, answer);
  }

  @Override
  public MacroManagerDisplay showManageMacros(MacroManagerInput input) {
    return view.showManageMacros(input);
  }

  @Override
  public void askBinaryOpen(String name, Consumer<BinaryChoice> answer) {
    view.askBinaryOpen(name, answer);
  }

  @Override
  public void showTooLarge(String message, Runnable openInHexViewer) {
    view.showTooLarge(message, openInHexViewer);
  }

  @Override
  public HexView hexPane(TabView tab, HexInput input) {
    return view.hexPane(tab, input);
  }

  @Override
  public void showHexMode(TabView tab, HexMode mode) {
    view.showHexMode(tab, mode);
  }

  @Override
  public HexView openHexViewer(String title, HexInput input) {
    return view.openHexViewer(title, input);
  }

  @Override
  public void askUnsaved(String name, Consumer<UnsavedChoice> answer) {
    view.askUnsaved(name, answer);
  }

  @Override
  public void askReplaceOpenFile(String name, Runnable replace, Runnable declined) {
    view.askReplaceOpenFile(name, replace, declined);
  }

  @Override
  public void askGoToLine(int lines, Predicate<String> valid, Consumer<String> answer) {
    view.askGoToLine(lines, valid, answer);
  }

  @Override
  public void askTabWidth(int current, int max, IntConsumer answer) {
    view.askTabWidth(current, max, answer);
  }

  @Override
  public void askReload(String name, Runnable reload, Runnable declined) {
    view.askReload(name, reload, declined);
  }

  @Override
  public void showSearchDialog(SearchForm form, Consumer<SearchForm> run) {
    view.showSearchDialog(form, run);
  }

  @Override
  public void resultsChanged(boolean structure) {
    view.resultsChanged(structure);
  }

  @Override
  public void revealResult(int set, int file, int match) {
    view.revealResult(set, file, match);
  }

  @Override
  public void showReplacePreview(ReplaceInput input) {
    view.showReplacePreview(input);
  }

  @Override
  public void replacePreviewChanged() {
    view.replacePreviewChanged();
  }

  @Override
  public void showReplaceReport(ReportInput input) {
    view.showReplaceReport(input);
  }

  @Override
  public void replaceReportChanged(ReportInput input) {
    view.replaceReportChanged(input);
  }

  @Override
  public void chooseReplaceRun(List<String> runs, Consumer<Integer> chosen) {
    view.chooseReplaceRun(runs, chosen);
  }

  @Override
  public void bookmarksChanged() {
    view.bookmarksChanged();
  }

  @Override
  public void askBookmarkComment(int line, String current, int maxLength, Consumer<String> answer) {
    view.askBookmarkComment(line, current, maxLength, answer);
  }

  @Override
  public void workspaceChanged(WorkspaceNode directory) {
    view.workspaceChanged(directory);
  }

  @Override
  public void revealWorkspace(List<WorkspaceNode> chain) {
    view.revealWorkspace(chain);
  }

  @Override
  public void askFileName(
      String title, String message, String name, int selectEnd, Consumer<String> answer) {
    view.askFileName(title, message, name, selectEnd, answer);
  }

  @Override
  public void askDelete(String message, Runnable delete) {
    view.askDelete(message, delete);
  }

  @Override
  public void askOpenMany(int count, Runnable open) {
    view.askOpenMany(count, open);
  }

  @Override
  public void showQuickOpen(QuickOpenInput input) {
    view.showQuickOpen(input);
  }

  @Override
  public void quickOpenChanged() {
    view.quickOpenChanged();
  }

  @Override
  public OsTraits system() {
    return view.system();
  }

  @Override
  public void askExternalChange(String name, boolean saving, Consumer<ChangeChoice> answer) {
    view.askExternalChange(name, saving, answer);
  }

  @Override
  public void chooseDocument(List<String> names, Consumer<Integer> chosen) {
    view.chooseDocument(names, chosen);
  }

  @Override
  public void showComparison(Comparison comparison) {
    view.showComparison(comparison);
  }

  @Override
  public int[] sideWeights() {
    return view.sideWeights();
  }

  @Override
  public void setSideWeights(int[] weights) {
    view.setSideWeights(weights);
  }

  @Override
  public int[] dockWeights() {
    return view.dockWeights();
  }

  @Override
  public void setDockWeights(int[] weights) {
    view.setDockWeights(weights);
  }

  @Override
  public int[] resultsWeights() {
    return view.resultsWeights();
  }

  @Override
  public void setResultsWeights(int[] weights) {
    view.setResultsWeights(weights);
  }

  @Override
  public List<WorkspaceNode> expandedWorkspace() {
    return view.expandedWorkspace();
  }

  @Override
  public void expandWorkspace(WorkspaceNode directory) {
    view.expandWorkspace(directory);
  }

  @Override
  public void showRecovery(RecoveryInput input) {
    view.showRecovery(input);
  }

  @Override
  public void recoveryChanged() {
    view.recoveryChanged();
  }

  @Override
  public void outlineChanged() {
    view.outlineChanged();
  }
}
