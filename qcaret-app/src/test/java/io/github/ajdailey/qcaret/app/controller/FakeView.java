package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import io.github.ajdailey.qcaret.app.view.BookmarksInput;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.EditorView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.app.view.PreferencesInput;
import io.github.ajdailey.qcaret.app.view.PrintTarget;
import io.github.ajdailey.qcaret.app.view.QuickOpenInput;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.ReplaceInput;
import io.github.ajdailey.qcaret.app.view.ReportInput;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.StatisticsDisplay;
import io.github.ajdailey.qcaret.app.view.StatisticsInput;
import io.github.ajdailey.qcaret.app.view.StatisticsReport;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.TabView;
import io.github.ajdailey.qcaret.app.view.UnsavedChoice;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * Records what the controllers show and holds each prompt's callback for the test to answer; every
 * call fails off the dispatcher's UI thread.
 */
final class FakeView implements MainView {
  /** What the print dialog returns; null is a canceled dialog. */
  PrintTarget printTarget;

  /** The print row's text, null while hidden. */
  String printRow;

  /** What the print row's Cancel runs. */
  Runnable printCancel;

  /** The highlights, bookmarks, marks and completion list a view was given. */
  static final class Shown {
    int[] brackets = new int[0];
    int[] occurrences = new int[0];
    Bookmarks bookmarks;
    boolean whitespace;
    boolean lineEndings;
    List<String> completions = List.of();
    int completionSelected;
    final List<Boolean> busy = new ArrayList<Boolean>();
    OverviewMarks overview = OverviewMarks.EMPTY;
    boolean overviewShown;
  }

  /** One editor view: what the controller asked of it. */
  final class FakeEditor implements EditorView {
    final EditorInput editor;
    int topRow;
    int caretShown;
    int scrolledTo = -1;
    int focused;
    int[] matches = new int[0];
    int tabWidth;
    boolean columnMode;
    TokenSource syntax;
    int redraws;
    final Shown shown = new Shown();

    FakeEditor(EditorInput editor) {
      this.editor = editor;
    }

    @Override
    public int topRow() {
      return topRow;
    }

    @Override
    public void showCaret() {
      ui.checkUiThread();
      caretShown++;
    }

    @Override
    public void scrollToRow(int row) {
      ui.checkUiThread();
      scrolledTo = row;
      topRow = row;
    }

    @Override
    public void focus() {
      ui.checkUiThread();
      focused++;
    }

    @Override
    public void showMatches(int[] ranges) {
      ui.checkUiThread();
      matches = ranges.clone();
    }

    @Override
    public ViewLayout layout() {
      ui.checkUiThread();
      return LAYOUT;
    }

    @Override
    public void setTabWidth(int columns) {
      ui.checkUiThread();
      tabWidth = columns;
    }

    @Override
    public void setColumnMode(boolean on) {
      ui.checkUiThread();
      columnMode = on;
    }

    @Override
    public void setSyntax(TokenSource tokens) {
      ui.checkUiThread();
      syntax = tokens;
    }

    @Override
    public void redrawHighlights() {
      ui.checkUiThread();
      redraws++;
    }

    @Override
    public void showBrackets(int[] ranges) {
      ui.checkUiThread();
      shown.brackets = ranges.clone();
    }

    @Override
    public void showOccurrences(int[] ranges) {
      ui.checkUiThread();
      shown.occurrences = ranges.clone();
    }

    @Override
    public void setBookmarks(Bookmarks bookmarks) {
      ui.checkUiThread();
      shown.bookmarks = bookmarks;
    }

    @Override
    public void setMarks(boolean whitespace, boolean lineEndings) {
      ui.checkUiThread();
      shown.whitespace = whitespace;
      shown.lineEndings = lineEndings;
    }

    @Override
    public void setBusy(boolean busy) {
      ui.checkUiThread();
      shown.busy.add(busy);
    }

    @Override
    public void showCompletions(List<String> words, int selected) {
      ui.checkUiThread();
      shown.completions = List.copyOf(words);
      shown.completionSelected = selected;
    }

    @Override
    public void showOverview(OverviewMarks marks) {
      ui.checkUiThread();
      shown.overview = marks;
    }

    @Override
    public void setOverviewShown(boolean value) {
      ui.checkUiThread();
      shown.overviewShown = value;
    }
  }

  /** A tab: its title, its views and what the controller asked of it. */
  final class FakeTab implements TabView, EditorView {
    int topRow;
    int scrolledTo = -1;
    int[] weights = {1, 1};
    final DisplayProjection projection;
    final SelectionModel selections;
    final EditorInput editor;
    final TabInput input;
    String title;
    String toolTip;
    int caretShown;
    boolean closed;
    int tabWidth;
    boolean columnMode;
    int[] matches = new int[0];
    final List<FakeEditor> splits = new ArrayList<FakeEditor>();
    boolean sideBySide;
    TokenSource syntax;
    int redraws;
    final Shown shown = new Shown();

    FakeTab(
        DisplayProjection projection,
        SelectionModel selections,
        EditorInput editor,
        TabInput input) {
      this.projection = projection;
      this.selections = selections;
      this.editor = editor;
      this.input = input;
    }

    @Override
    public int topRow() {
      return topRow;
    }

    @Override
    public void setTitle(String value, String tip) {
      ui.checkUiThread();
      title = value;
      toolTip = tip;
    }

    @Override
    public void select() {
      ui.checkUiThread();
      selected = this;
      input.activated();
    }

    @Override
    public void showCaret() {
      ui.checkUiThread();
      caretShown++;
    }

    @Override
    public void scrollToRow(int row) {
      ui.checkUiThread();
      scrolledTo = row;
      topRow = row;
    }

    @Override
    public void focus() {
      ui.checkUiThread();
    }

    @Override
    public void showMatches(int[] ranges) {
      ui.checkUiThread();
      matches = ranges.clone();
    }

    @Override
    public EditorView firstView() {
      return this;
    }

    @Override
    public void setSyntax(TokenSource tokens) {
      ui.checkUiThread();
      syntax = tokens;
    }

    @Override
    public void redrawHighlights() {
      ui.checkUiThread();
      redraws++;
    }

    @Override
    public EditorView split(
        DisplayProjection splitProjection,
        SelectionModel splitSelections,
        EditorInput splitEditor,
        boolean side) {
      ui.checkUiThread();
      FakeEditor view = new FakeEditor(splitEditor);
      splits.add(view);
      sideBySide = side;
      return view;
    }

    @Override
    public void setSideBySide(boolean side) {
      ui.checkUiThread();
      sideBySide = side;
    }

    @Override
    public int[] splitWeights() {
      ui.checkUiThread();
      return weights.clone();
    }

    @Override
    public void setSplitWeights(int[] value) {
      ui.checkUiThread();
      weights = value.clone();
    }

    @Override
    public void unsplit(EditorView kept) {
      ui.checkUiThread();
      splits.removeIf(
          new Predicate<FakeEditor>() {
            @Override
            public boolean test(FakeEditor view) {
              return view != kept;
            }
          });
    }

    @Override
    public void close() {
      ui.checkUiThread();
      closed = true;
      tabs.remove(this);
      if (selected == this) {
        selected = tabs.isEmpty() ? null : tabs.get(tabs.size() - 1);
        if (selected != null) {
          selected.input.activated();
        }
      }
    }

    @Override
    public ViewLayout layout() {
      ui.checkUiThread();
      return LAYOUT;
    }

    @Override
    public void setTabWidth(int columns) {
      ui.checkUiThread();
      tabWidth = columns;
    }

    @Override
    public void setColumnMode(boolean on) {
      ui.checkUiThread();
      columnMode = on;
    }

    @Override
    public void showBrackets(int[] ranges) {
      ui.checkUiThread();
      shown.brackets = ranges.clone();
    }

    @Override
    public void showOccurrences(int[] ranges) {
      ui.checkUiThread();
      shown.occurrences = ranges.clone();
    }

    @Override
    public void setBookmarks(Bookmarks bookmarks) {
      ui.checkUiThread();
      shown.bookmarks = bookmarks;
    }

    @Override
    public void setMarks(boolean whitespace, boolean lineEndings) {
      ui.checkUiThread();
      shown.whitespace = whitespace;
      shown.lineEndings = lineEndings;
    }

    @Override
    public void setBusy(boolean busy) {
      ui.checkUiThread();
      shown.busy.add(busy);
    }

    @Override
    public void showCompletions(List<String> words, int selected) {
      ui.checkUiThread();
      shown.completions = List.copyOf(words);
      shown.completionSelected = selected;
    }

    @Override
    public void showOverview(OverviewMarks marks) {
      ui.checkUiThread();
      shown.overview = marks;
    }

    @Override
    public void setOverviewShown(boolean value) {
      ui.checkUiThread();
      shown.overviewShown = value;
    }
  }

  /** A grid of 10 by 10 pixel cells, five rows to a page; row and column from x and y alone. */
  static final ViewLayout LAYOUT =
      new ViewLayout() {
        @Override
        public int pageRows() {
          return 5;
        }

        @Override
        public int rowAt(int y) {
          return Math.max(0, y / 10);
        }

        @Override
        public int columnAt(int row, int x) {
          return Math.max(0, (x + 5) / 10);
        }
      };

  final List<FakeTab> tabs = new ArrayList<FakeTab>();
  FakeTab selected;
  Consumer<List<Path>> openChooser;
  Consumer<Path> saveChooser;
  String saveName;
  Consumer<UnsavedChoice> unsaved;
  String unsavedName;
  final ManualUiDispatcher ui;
  final List<String> infos = new ArrayList<String>();
  WindowGeometry.Request bounds = new WindowGeometry.Request(null, null, null, null);
  boolean maximized;
  boolean hidden;
  boolean disposed;
  int raised;
  String textTitle;
  String text;
  AboutInfo about;
  String clipboard;
  int stateChanges;
  String binaryName;
  Runnable openBinary;
  final AtomicInteger active = new AtomicInteger();
  final List<String> notices = new ArrayList<String>();
  final List<String> launched = new ArrayList<String>();
  Boolean toolbarShown;
  Boolean lineNumbersShown;
  final List<Path> guides = new ArrayList<Path>();
  boolean guideWindow;
  String log;
  boolean warningMarker;
  int fontSize;
  String fontFamily = "";
  Keymap keymap;
  PreferencesInput preferences;
  int goToLines;
  Consumer<String> goTo;
  Keymap shortcuts;
  PaletteInput palette;
  SearchForm searchForm;
  Consumer<SearchForm> search;
  Consumer<Path> folderChooser;
  ResultsInput results;
  boolean resultsShown;
  BookmarksInput bookmarkList;
  boolean bookmarksShown;
  int bookmarksChanges;
  int commentLine = -1;
  String commentCurrent;
  int commentLimit;
  Consumer<String> comment;
  int resultsChanges;
  int[] revealed;
  String replaceOpenName;
  Runnable replaceOpen;
  Runnable replaceDeclined;
  ReplaceInput replacePreview;
  int previewChanges;
  final List<ReportInput> reports = new ArrayList<ReportInput>();
  List<String> runChoices;
  Consumer<Integer> runChooser;
  final List<String> changePrompts = new ArrayList<String>();
  Consumer<ChangeChoice> changeAnswer;
  List<String> documentChoices;
  Consumer<Integer> documentChooser;

  /** The right margin column set, 0 while hidden. */
  int rightMargin = -1;

  /** The open statistics dialog's state. */
  static final class Statistics implements StatisticsDisplay {
    final StatisticsInput input;
    final List<Integer> progress = new ArrayList<Integer>();
    StatisticsReport report;
    String message;
    int fronted;

    Statistics(StatisticsInput input) {
      this.input = input;
    }

    @Override
    public void showProgress(int percent) {
      progress.add(percent);
    }

    @Override
    public void showReport(StatisticsReport value) {
      report = value;
      message = null;
    }

    @Override
    public void showMessage(String value) {
      message = value;
      report = null;
    }

    @Override
    public void toFront() {
      fronted++;
    }
  }

  Statistics statistics;
  List<String> dateRows;
  int dateSelected = -1;
  Consumer<Integer> dateChooser;
  final List<Comparison> comparisons = new ArrayList<Comparison>();

  FakeView(ManualUiDispatcher ui) {
    this.ui = ui;
  }

  @Override
  public void toFront() {
    raised++;
  }

  @Override
  public void hide() {
    ui.checkUiThread();
    hidden = true;
  }

  @Override
  public void dispose() {
    ui.checkUiThread();
    disposed = true;
  }

  @Override
  public void showInfo(String title, String message) {
    ui.checkUiThread();
    infos.add(title + ": " + message);
  }

  @Override
  public void showText(String title, String value) {
    ui.checkUiThread();
    textTitle = title;
    text = value;
  }

  @Override
  public void showAbout(AboutInfo info) {
    ui.checkUiThread();
    about = info;
  }

  @Override
  public String toolkitDescription() {
    ui.checkUiThread();
    return "mock toolkit";
  }

  @Override
  public WindowGeometry.Request windowBounds() {
    ui.checkUiThread();
    return bounds;
  }

  @Override
  public boolean windowMaximized() {
    ui.checkUiThread();
    return maximized;
  }

  @Override
  public void setText(String value) {
    ui.checkUiThread();
    clipboard = value;
  }

  @Override
  public String clipboardText() {
    ui.checkUiThread();
    return clipboard;
  }

  @Override
  public TabView addTab(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      TabInput tab,
      int index) {
    ui.checkUiThread();
    FakeTab fake = new FakeTab(projection, selections, editor, tab);
    tabs.add(index, fake);
    selected = fake;
    return fake;
  }

  @Override
  public void editStateChanged() {}

  @Override
  public void documentStateChanged() {
    ui.checkUiThread();
    stateChanges++;
  }

  @Override
  public void askOpenBinary(String name, Runnable open) {
    ui.checkUiThread();
    binaryName = name;
    openBinary = open;
  }

  @Override
  public void setActive() {
    active.incrementAndGet();
  }

  @Override
  public void setIdle() {
    active.decrementAndGet();
  }

  @Override
  public void showNotice(String message) {
    ui.checkUiThread();
    notices.add(message);
  }

  @Override
  public void showLog(String value) {
    ui.checkUiThread();
    log = value;
  }

  @Override
  public void showWarningMarker(boolean shown) {
    ui.checkUiThread();
    warningMarker = shown;
  }

  @Override
  public boolean showGuide(Path file) {
    guides.add(file);
    return guideWindow;
  }

  @Override
  public boolean launch(Path target) {
    ui.checkUiThread();
    launched.add(target.toString());
    return true;
  }

  @Override
  public boolean launchUrl(String url) {
    ui.checkUiThread();
    launched.add(url);
    return true;
  }

  @Override
  public void setFontSize(int points) {
    ui.checkUiThread();
    fontSize = points;
  }

  @Override
  public void setFontFamily(String family) {
    ui.checkUiThread();
    fontFamily = family;
  }

  @Override
  public void setToolbarShown(boolean shown) {
    ui.checkUiThread();
    toolbarShown = shown;
  }

  @Override
  public void setLineNumbersShown(boolean shown) {
    ui.checkUiThread();
    lineNumbersShown = shown;
  }

  @Override
  public int defaultFontSize() {
    return 10;
  }

  @Override
  public void showPreferences(PreferencesInput input) {
    ui.checkUiThread();
    preferences = input;
  }

  @Override
  public void setKeymap(Keymap bindings) {
    ui.checkUiThread();
    keymap = bindings;
  }

  @Override
  public void chooseFilesToOpen(Path directory, Consumer<List<Path>> chosen) {
    ui.checkUiThread();
    openChooser = chosen;
  }

  @Override
  public void chooseSaveTarget(Path directory, String name, Consumer<Path> chosen) {
    ui.checkUiThread();
    saveName = name;
    saveChooser = chosen;
  }

  @Override
  public void askUnsaved(String name, Consumer<UnsavedChoice> answer) {
    ui.checkUiThread();
    unsavedName = name;
    unsaved = answer;
  }

  @Override
  public void askReplaceOpenFile(String name, Runnable replace, Runnable declined) {
    ui.checkUiThread();
    replaceOpenName = name;
    replaceOpen = replace;
    replaceDeclined = declined;
  }

  int tabWidthAsked;
  IntConsumer tabWidthAnswer;
  String reloadName;
  Runnable reloadAccept;
  Runnable reloadDecline;
  String loadText;
  int loadPercent = -1;
  Runnable loadCancel;
  int loadHides;

  @Override
  public void askTabWidth(int current, int max, IntConsumer answer) {
    ui.checkUiThread();
    tabWidthAsked = current;
    tabWidthAnswer = answer;
  }

  @Override
  public void askReload(String name, Runnable reload, Runnable declined) {
    ui.checkUiThread();
    reloadName = name;
    reloadAccept = reload;
    reloadDecline = declined;
  }

  @Override
  public void showLoadProgress(String text, int percent, Runnable cancel) {
    ui.checkUiThread();
    loadText = text;
    loadPercent = percent;
    loadCancel = cancel;
  }

  @Override
  public void hideLoadProgress() {
    ui.checkUiThread();
    loadText = null;
    loadCancel = null;
    loadHides++;
  }

  @Override
  public void askGoToLine(int lines, Consumer<String> answer) {
    ui.checkUiThread();
    goToLines = lines;
    goTo = answer;
  }

  @Override
  public void showKeyboardShortcuts(Keymap keymap) {
    ui.checkUiThread();
    shortcuts = keymap;
  }

  @Override
  public void showCommandPalette(PaletteInput input) {
    ui.checkUiThread();
    palette = input;
  }

  @Override
  public void showSearchDialog(SearchForm form, Consumer<SearchForm> run) {
    ui.checkUiThread();
    searchForm = form;
    search = run;
  }

  @Override
  public void chooseFolder(Path start, Consumer<Path> chosen) {
    ui.checkUiThread();
    folderChooser = chosen;
  }

  // Workspace browser and Quick Open.

  WorkspaceInput workspace;
  boolean workspaceShown;
  final List<WorkspaceNode> workspaceChanges = new ArrayList<WorkspaceNode>();
  List<WorkspaceNode> revealedChain;
  QuickOpenInput quickOpen;
  int quickOpenChanges;
  boolean windows;

  @Override
  public void showWorkspace(WorkspaceInput input, boolean shown) {
    ui.checkUiThread();
    workspace = input;
    workspaceShown = shown;
  }

  @Override
  public void workspaceChanged(WorkspaceNode directory) {
    ui.checkUiThread();
    workspaceChanges.add(directory);
  }

  @Override
  public void revealWorkspace(List<WorkspaceNode> chain) {
    ui.checkUiThread();
    revealedChain = chain;
  }

  String fileNameAsked;
  String fileNameInitial;
  int fileNameSelectEnd;
  Consumer<String> fileNameAnswer;
  String deleteAsked;
  Runnable deleteAccept;

  @Override
  public void askFileName(
      String title, String message, String name, int selectEnd, Consumer<String> answer) {
    ui.checkUiThread();
    fileNameAsked = title + ": " + message;
    fileNameInitial = name;
    fileNameSelectEnd = selectEnd;
    fileNameAnswer = answer;
  }

  @Override
  public void askDelete(String message, Runnable delete) {
    ui.checkUiThread();
    deleteAsked = message;
    deleteAccept = delete;
  }

  @Override
  public void showQuickOpen(QuickOpenInput input) {
    ui.checkUiThread();
    quickOpen = input;
  }

  @Override
  public void quickOpenChanged() {
    ui.checkUiThread();
    quickOpenChanges++;
  }

  @Override
  public boolean launchesByExtension() {
    ui.checkUiThread();
    return windows;
  }

  @Override
  public void showBookmarks(BookmarksInput input, boolean shown) {
    ui.checkUiThread();
    bookmarkList = input;
    bookmarksShown = shown;
  }

  @Override
  public void bookmarksChanged() {
    ui.checkUiThread();
    bookmarksChanges++;
  }

  @Override
  public void askBookmarkComment(int line, String current, int maxLength, Consumer<String> answer) {
    ui.checkUiThread();
    commentLine = line;
    commentCurrent = current;
    commentLimit = maxLength;
    comment = answer;
  }

  @Override
  public void showResults(ResultsInput input, boolean shown) {
    ui.checkUiThread();
    results = input;
    resultsShown = shown;
  }

  @Override
  public void resultsChanged(boolean structure) {
    ui.checkUiThread();
    resultsChanges++;
  }

  @Override
  public void revealResult(int set, int file, int match) {
    ui.checkUiThread();
    revealed = new int[] {set, file, match};
  }

  @Override
  public void showReplacePreview(ReplaceInput input) {
    ui.checkUiThread();
    replacePreview = input;
  }

  @Override
  public void replacePreviewChanged() {
    ui.checkUiThread();
    previewChanges++;
  }

  @Override
  public void showReplaceReport(ReportInput input) {
    ui.checkUiThread();
    reports.add(input);
  }

  @Override
  public void replaceReportChanged(ReportInput input) {
    ui.checkUiThread();
  }

  @Override
  public void chooseReplaceRun(List<String> runs, Consumer<Integer> chosen) {
    ui.checkUiThread();
    runChoices = runs;
    runChooser = chosen;
  }

  @Override
  public void askExternalChange(String name, boolean saving, Consumer<ChangeChoice> answer) {
    ui.checkUiThread();
    changePrompts.add((saving ? "save " : "") + name);
    changeAnswer = answer;
  }

  @Override
  public void setRightMargin(int column) {
    ui.checkUiThread();
    rightMargin = column;
  }

  @Override
  public StatisticsDisplay showStatistics(StatisticsInput input) {
    ui.checkUiThread();
    statistics = new Statistics(input);
    return statistics;
  }

  @Override
  public void chooseDateFormat(List<String> rows, int selected, Consumer<Integer> chosen) {
    ui.checkUiThread();
    dateRows = rows;
    dateSelected = selected;
    dateChooser = chosen;
  }

  @Override
  public void chooseDocument(List<String> names, Consumer<Integer> chosen) {
    ui.checkUiThread();
    documentChoices = names;
    documentChooser = chosen;
  }

  @Override
  public void showComparison(Comparison comparison) {
    ui.checkUiThread();
    comparisons.add(comparison);
  }

  // Session state and recovery.

  int[] sideWeights = {1, 3};
  int[] dockWeights = {3, 1};
  int[] resultsWeights = {3, 2};
  final List<WorkspaceNode> expanded = new ArrayList<WorkspaceNode>();
  RecoveryInput recovery;
  int recoveryChanges;

  @Override
  public int[] sideWeights() {
    ui.checkUiThread();
    return sideWeights.clone();
  }

  @Override
  public void setSideWeights(int[] weights) {
    ui.checkUiThread();
    sideWeights = weights.clone();
  }

  @Override
  public int[] resultsWeights() {
    ui.checkUiThread();
    return resultsWeights.clone();
  }

  @Override
  public void setResultsWeights(int[] weights) {
    ui.checkUiThread();
    resultsWeights = weights.clone();
  }

  @Override
  public int[] dockWeights() {
    ui.checkUiThread();
    return dockWeights.clone();
  }

  @Override
  public void setDockWeights(int[] weights) {
    ui.checkUiThread();
    dockWeights = weights.clone();
  }

  @Override
  public List<WorkspaceNode> expandedWorkspace() {
    ui.checkUiThread();
    return List.copyOf(expanded);
  }

  @Override
  public void expandWorkspace(WorkspaceNode directory) {
    ui.checkUiThread();
    expanded.add(directory);
  }

  @Override
  public void showRecovery(RecoveryInput input) {
    ui.checkUiThread();
    recovery = input;
  }

  @Override
  public void recoveryChanged() {
    ui.checkUiThread();
    recoveryChanges++;
  }

  @Override
  public void choosePrinter(Consumer<PrintTarget> chosen) {
    ui.checkUiThread();
    if (printTarget != null) {
      chosen.accept(printTarget);
    }
  }

  @Override
  public void showPrintProgress(String text, int percent, Runnable cancel) {
    ui.checkUiThread();
    printRow = text;
    printCancel = cancel;
  }

  @Override
  public void hidePrintProgress() {
    ui.checkUiThread();
    printRow = null;
  }
}
