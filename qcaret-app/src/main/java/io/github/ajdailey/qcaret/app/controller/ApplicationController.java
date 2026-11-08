package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Options;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.TabInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.workspace.HomeFolder;
import io.github.ajdailey.qcaret.core.workspace.NameFilter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one main window's commands and owns its {@link DocumentController} and per-window
 * controllers; what the windows share is in {@link WindowSet}. Confined to the UI thread; file
 * reads and settings writes run on {@code qcaret-lifecycle-} workers. Edit commands from the menu
 * go to the selected editor.
 *
 * <p>{@link CommandId#QUIT} closes the documents of every window, asking about each dirty one; a
 * Cancel keeps the windows open. {@link #quit} itself asks nothing: the shutdown hook calls it. The
 * session and the recovery files span the windows and are in {@link WindowSet}.
 *
 * <p>Help commands run through {@link HelpController}. Menu check and enable state is read through
 * {@link CommandStates}. A zoom command steps {@code editor.fontSize} by one point.
 *
 * <p>{@link Settings} holds the settings and saves each change on a worker; {@link #apply} brings
 * this window's controllers and view in line with a change, wherever it came from. A window that
 * closes records its normal bounds and maximized state unless {@code rememberWindow} is false (a
 * {@code --window-*} option or {@code -m} was given).
 */
public final class ApplicationController {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

  private final WindowSet set;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final Settings settings;
  private final ThemeController theme;
  private final DocumentController documents;
  private final HelpController help;
  private final FindController find;
  private final WorkspaceController workspace;
  private final SearchController search;
  private final ReplaceController replace;
  private final BookmarkList bookmarkList;
  private final NavigationHistory history;
  private final RecentFiles recentFiles;
  private final CompareController compare;
  private final PrintController print;
  private final GrammarLibrary grammars;
  private final PreferencesController preferences;
  private final StatisticsController statistics;
  private final DateTimeInsert dateTime;
  private final EditorAids aids;
  private final Settings.Listener settingsListener =
      new Settings.Listener() {
        @Override
        public void changed(Preferences before, Preferences after) {
          apply(before, after);
        }
      };
  private boolean quitting;

  /**
   * Creates a controller in a set of its own.
   *
   * @param workers the thread owner; {@link #quit} closes it
   * @param ui the route onto the UI thread
   * @param view the main window
   * @param config the settings store
   * @param preferences the settings loaded at startup
   * @param system the system's light or dark setting
   * @param budget the documents' heap share
   * @param keymap the default key bindings; the settings' overrides apply on top
   * @param rememberWindow false when the quit saves nothing
   */
  public ApplicationController(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      ConfigStore config,
      Preferences preferences,
      ThemeService system,
      MemoryBudget budget,
      Keymap keymap,
      boolean rememberWindow) {
    this(
        new WindowSet(workers, ui, config, preferences, system, budget, keymap, rememberWindow),
        view);
  }

  /**
   * Creates a window's controller and joins the set.
   *
   * @param set the windows of the instance
   * @param view the main window
   */
  public ApplicationController(WindowSet set, MainView view) {
    this.set = set;
    this.workers = set.workers();
    this.ui = set.ui();
    this.view = view;
    this.settings = set.settings();
    this.theme = set.theme();
    ConfigStore config = set.config();
    MemoryBudget budget = set.budget();
    Preferences preferences = settings.current();
    this.documents = new DocumentController(workers, ui, view, budget, LoadOptions.DEFAULTS);
    this.help =
        new HelpController(
            workers,
            ui,
            view,
            config.location(),
            set.keymap(),
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return quitting;
              }
            },
            set);
    this.find = new FindController(documents, workers, ui, settings);
    this.workspace = new WorkspaceController(view, workers, ui, documents, budget);
    this.history = new NavigationHistory(documents, workers, ui, view);
    this.recentFiles = new RecentFiles(documents, view, set.recentPaths());
    Options options = preferences.options();
    HyperSearch.Limits limits =
        HyperSearch.Limits.DEFAULTS.withMaxLineChars(options.searchMaxLineChars());
    this.search =
        new SearchController(documents, workspace, workers, ui, view, budget, limits, settings);
    workspace.setFiles(
        new WorkspaceFiles(
            workspace, view, workers, ui, documents, recentFiles, set.session(), search));
    workspace.setBrowse(
        new WorkspaceBrowse(
            workspace,
            view,
            workers,
            ui,
            settings,
            HomeFolder.find(),
            NameFilter.PLATFORM_IGNORES_CASE));
    workspace.setPeers(set.browsers());
    this.replace =
        new ReplaceController(
            documents,
            workers,
            ui,
            view,
            budget,
            limits,
            config.location().directory().resolve(ReplaceBackups.DIRECTORY),
            options.backupDays());
    search.setReplace(replace);
    this.bookmarkList = new BookmarkList(documents, workers, ui, view, budget);
    this.compare = new CompareController(documents, workers, ui, view, budget);
    this.print = new PrintController(documents, workers, ui, view);
    documents.setCompare(compare);
    documents.setOverviewSources(
        new OverviewSources() {
          @Override
          public TextMatcher findMatcher(Document document) {
            return find.overviewMatcher(document);
          }

          @Override
          public int[] searchOffsets(Document document) {
            return search.offsets(document);
          }
        });
    this.grammars = set.grammars();
    documents.setGrammars(grammars);
    documents.setCompletionChars(options.completionChars());
    documents.setAutoClose(options.autoClose());
    documents.setTabDefaults(options.tabWidth(), options.insertSpaces());
    documents.setFileSettings(options.editorConfig(), options.modelines());
    documents.setMaxHighlightLineChars(options.maxHighlightLineChars());
    documents.setPollSeconds(options.pollSeconds());
    documents.setAutoReloadClean(options.autoReloadClean());
    documents.setGrammarChosen(
        new Consumer<String>() {
          @Override
          public void accept(String id) {
            rememberGrammar(id);
          }
        });
    workspace.setExcludedDirectories(options.excludedDirectories());
    this.preferences =
        new PreferencesController(settings, view, set.defaultKeymap(), set::forgetHistory, set);
    this.statistics = new StatisticsController(documents, workers, ui, view);
    this.dateTime =
        new DateTimeInsert(documents, settings, view, workers, ui, Clock.systemDefaultZone());
    view.setRightMargin(rightMarginColumn());
    this.aids = new EditorAids(settings, view, documents);
    settings.addListener(settingsListener);
    set.register(this);
    if (set.recent() != null) {
      logging(set.recent(), set.logFiles());
    }
  }

  /** Brings this window's controllers and view in line with changed settings. */
  private void apply(Preferences before, Preferences after) {
    Options old = before.options();
    Options now = after.options();
    if (now.completionChars() != old.completionChars()) {
      documents.setCompletionChars(now.completionChars());
    }
    if (now.autoClose() != old.autoClose()) {
      documents.setAutoClose(now.autoClose());
    }
    if (now.maxHighlightLineChars() != old.maxHighlightLineChars()) {
      documents.setMaxHighlightLineChars(now.maxHighlightLineChars());
    }
    if (now.fontSize() != old.fontSize()) {
      view.setFontSize(now.fontSize());
    }
    if (!after.appearance().fontFamily().equals(before.appearance().fontFamily())) {
      view.setFontFamily(after.appearance().fontFamily());
    }
    documents.setTabDefaults(now.tabWidth(), now.insertSpaces());
    documents.setFileSettings(now.editorConfig(), now.modelines());
    if (now.pollSeconds() != old.pollSeconds()) {
      documents.setPollSeconds(now.pollSeconds());
    }
    if (now.autoReloadClean() != old.autoReloadClean()) {
      documents.setAutoReloadClean(now.autoReloadClean());
    }
    if (now.backupDays() != old.backupDays()) {
      replace.setBackupDays(now.backupDays());
    }
    if (now.searchMaxLineChars() != old.searchMaxLineChars()) {
      search.setMaxLineChars(now.searchMaxLineChars());
      replace.setMaxLineChars(now.searchMaxLineChars());
    }
    if (!now.excludedDirectories().equals(old.excludedDirectories())) {
      workspace.setExcludedDirectories(now.excludedDirectories());
    }
    if (!after.state().search().equals(before.state().search())) {
      find.togglesChanged();
    }
    if (!after.state().texts().equals(before.state().texts())
        || !after.state().replacements().equals(before.state().replacements())) {
      find.historyChanged();
    }
    if (after.state().toolbar() != before.state().toolbar()) {
      view.setToolbarShown(after.state().toolbar());
    }
    if (after.state().rightMargin() != before.state().rightMargin()
        || now.rightMargin() != old.rightMargin()) {
      view.setRightMargin(rightMarginColumn());
    }
    aids.apply(before, after);
    if (!after.shortcuts().equals(before.shortcuts())) {
      help.setKeymap(set.keymap());
      view.setKeymap(set.keymap());
    }
  }

  /** Says that native controls show the theme from the next start. */
  void showThemeRestart() {
    view.showInfo(UiText.THEME_RESTART_TITLE.text(), UiText.THEME_RESTART_MESSAGE.text());
  }

  private void rememberGrammar(String id) {
    Preferences current = settings.current();
    List<String> recent = new ArrayList<String>(current.state().recentGrammars());
    recent.remove(id);
    recent.add(0, id);
    settings.set(current.withState(current.state().withRecentGrammars(recent)));
  }

  /**
   * Returns the running settings.
   *
   * @return the settings
   */
  public Settings settings() {
    return settings;
  }

  /**
   * Returns a dialog's remembered size.
   *
   * @param name the dialog's name
   * @return its size, or null when none is remembered
   */
  public DialogSize dialogSize(String name) {
    UiState.Size size = settings.current().state().dialogs().get(name);
    return size == null ? null : new DialogSize(size.width(), size.height());
  }

  /**
   * Remembers a dialog's size.
   *
   * @param name the dialog's name, {@code [A-Za-z0-9]+}
   * @param size its size
   */
  public void rememberDialogSize(String name, DialogSize size) {
    if (!quitting) {
      Preferences current = settings.current();
      UiState.Size kept = new UiState.Size(size.width(), size.height());
      settings.set(current.withState(current.state().withDialog(name, kept)));
    }
  }

  /**
   * Returns {@code editor.fontSize}.
   *
   * @return the size in points, 0 for the platform's default
   */
  public int fontSize() {
    return settings.current().options().fontSize();
  }

  /**
   * Returns {@code editor.fontFamily}.
   *
   * @return the family, empty for the platform's monospace font
   */
  public String fontFamily() {
    return settings.current().appearance().fontFamily();
  }

  /**
   * Returns the grammars.
   *
   * @return the grammar library
   */
  public GrammarLibrary grammars() {
    return grammars;
  }

  /** Creates Quick Open, over the open documents only for Switch Document. */
  private QuickOpen quickOpen(boolean documentsOnly, String initial) {
    return new QuickOpen(
        documents,
        workspace,
        workers,
        ui,
        view,
        documentsOnly,
        recentFiles.paths(),
        grammars,
        initial);
  }

  /** Reads the built-in and user grammars on a worker; call once the window is attached. */
  public void loadGrammars() {
    grammars.load();
  }

  /**
   * Uses a grammar for the selected document; see {@link DocumentController#setSyntax}.
   *
   * @param id the grammar's id
   */
  public void setSyntax(String id) {
    if (!quitting) {
      documents.setSyntax(id);
    }
  }

  /**
   * Returns the find bar's controller.
   *
   * @return the find controller
   */
  public FindController find() {
    return find;
  }

  /**
   * Returns the Bookmarks list's model.
   *
   * @return the list
   */
  public BookmarkList bookmarkList() {
    return bookmarkList;
  }

  /** Returns Go Back and Go Forward's positions; for {@link CommandStates}. */
  NavigationHistory history() {
    return history;
  }

  /**
   * Returns the recovery dialog's controller.
   *
   * @return the input
   */
  public RecoveryInput recoveryInput() {
    return set.recovery();
  }

  /** Returns the recovery files' controller; for tests. */
  RecoveryController recovery() {
    return set.recovery();
  }

  /** Returns the session's controller; for tests. */
  SessionController sessionController() {
    return set.session();
  }

  /** Returns File > Recent Files; for {@link CommandStates}. */
  RecentFiles recentFiles() {
    return recentFiles;
  }

  /**
   * Opens a file of File > Recent Files, or selects its tab.
   *
   * @param index the entry, newest first
   */
  public void openRecent(int index) {
    if (!quitting) {
      recentFiles.open(index);
    }
  }

  /**
   * Opens a file of File > Recently Closed, or selects its tab.
   *
   * @param index the entry, newest first
   */
  public void openClosed(int index) {
    if (!quitting) {
      documents.reopenClosed(index);
    }
  }

  /**
   * Opens files dropped on the window on workers, a tab each.
   *
   * @param paths the dropped paths, not yet validated
   */
  public void openDropped(List<Path> paths) {
    if (!quitting) {
      documents.open(paths);
    }
  }

  /**
   * Returns HyperSearch's controller.
   *
   * @return the search controller
   */
  public SearchController search() {
    return search;
  }

  /**
   * Returns the replace preview's and the replace runs' controller.
   *
   * @return the replace controller
   */
  public ReplaceController replace() {
    return replace;
  }

  /**
   * Returns the window's workspace folder.
   *
   * @return the workspace controller
   */
  public WorkspaceController workspace() {
    return workspace;
  }

  /**
   * Connects every window's Help > Show Log and warning marker to the recent log lines, and Help >
   * Debug Log File to the log file; see {@link WindowSet#setLogging}.
   *
   * @param lines the recent lines
   * @param files the debug log file
   */
  public void setLogging(RecentLog lines, LogFiles files) {
    set.setLogging(lines, files);
  }

  void logging(RecentLog lines, LogFiles files) {
    help.setLogging(lines, files);
    view.showWarningMarker(help.newWarnings());
  }

  void showWarningMarker(boolean shown) {
    if (!quitting) {
      view.showWarningMarker(shown);
    }
  }

  /**
   * Returns the key bindings.
   *
   * @return the keymap
   */
  public Keymap keymap() {
    return set.keymap();
  }

  /**
   * Starts listing every charset on a worker for the Encoding menu; the listener receives them on
   * the UI thread while the window is open. The list is computed once per run.
   *
   * @param listener receives the choices
   */
  public void loadEncodings(final EncodingChoices.Listener listener) {
    boolean started =
        workers.tryStart(
            Workers.Kind.LIFECYCLE,
            new Runnable() {
              @Override
              public void run() {
                final EncodingChoices choices = EncodingChoices.available();
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        if (!quitting) {
                          listener.loaded(choices);
                        }
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Encodings not listed: workers are closed");
    }
  }

  /** Reports whether the log goes to the debug log file; for {@link CommandStates}. */
  boolean debugLogFile() {
    return help.debugLogFile();
  }

  /** Returns the editor font size in points; for {@link CommandStates}. */
  int fontPoints() {
    int size = settings.current().options().fontSize();
    return size == 0 ? view.defaultFontSize() : size;
  }

  /**
   * Returns the window's documents.
   *
   * @return the document controller
   */
  public DocumentController documents() {
    return documents;
  }

  /**
   * Returns the theme, which pushes palette changes to the views.
   *
   * @return the theme controller
   */
  public ThemeController theme() {
    return theme;
  }

  /**
   * Returns the current settings.
   *
   * @return the settings
   */
  public Preferences preferences() {
    return settings.current();
  }

  /**
   * Reports whether {@link #quit} has run.
   *
   * @return true once quitting
   */
  public boolean isQuitting() {
    return quitting || set.isQuitting();
  }

  /**
   * Adds a listener that runs on the lifecycle worker once the quit has saved and closed the
   * workers.
   *
   * @param listener the listener
   */
  public void addFinishListener(Runnable listener) {
    set.addFinishListener(listener);
  }

  /**
   * Makes this the window handed files go to and checks its files for changes by other programs;
   * the window was activated.
   */
  public void windowActivated() {
    if (!quitting) {
      set.activated(this);
      documents.checkFiles();
    }
  }

  /**
   * Returns the comparison controller.
   *
   * @return the compare controller
   */
  public CompareController compare() {
    return compare;
  }

  /**
   * Runs a command.
   *
   * @param command the command
   */
  public void execute(CommandId command) {
    if (quitting) {
      return;
    }
    switch (command) {
      case NEW -> documents.newDocument();
      case OPEN -> documents.open();
      case SAVE -> documents.save();
      case SAVE_AS -> documents.saveAs();
      case SAVE_ALL -> documents.saveAll();
      case REOPEN_CLOSED -> documents.reopenClosed();
      case CLEAR_CLOSED_FILES -> documents.clearClosed();
      case RELOAD -> documents.reloadFromDisk();
      case PRINT -> print.print();
      case CLOSE -> documents.closeActive();
      case CLOSE_ALL -> documents.closeAll(null);
      case QUIT -> requestQuit();
      case NEW_WINDOW -> set.newWindow();
      case MOVE_TO_NEW_WINDOW -> moveActiveToNewWindow();
      case UNDO,
          REDO,
          CUT,
          COPY,
          PASTE,
          SELECT_ALL,
          NEWLINE,
          DELETE_BACK,
          DELETE_FORWARD,
          CANCEL,
          DUPLICATE,
          DELETE_LINE,
          MOVE_LINES_UP,
          MOVE_LINES_DOWN,
          JOIN_LINES,
          UPPER_CASE,
          LOWER_CASE,
          INDENT,
          OUTDENT,
          TOGGLE_LINE_COMMENT,
          TOGGLE_BLOCK_COMMENT,
          INSERT_TAB,
          ADD_CARET_ABOVE,
          ADD_CARET_BELOW,
          ADD_NEXT_OCCURRENCE,
          SPLIT_INTO_LINES,
          COLUMN_MODE,
          CARET_LEFT,
          CARET_RIGHT,
          CARET_UP,
          CARET_DOWN,
          WORD_LEFT,
          WORD_RIGHT,
          LINE_START,
          LINE_END,
          PAGE_UP,
          PAGE_DOWN,
          DOCUMENT_START,
          DOCUMENT_END,
          SELECT_LEFT,
          SELECT_RIGHT,
          SELECT_UP,
          SELECT_DOWN,
          SELECT_WORD_LEFT,
          SELECT_WORD_RIGHT,
          SELECT_LINE_START,
          SELECT_LINE_END,
          SELECT_PAGE_UP,
          SELECT_PAGE_DOWN,
          SELECT_DOCUMENT_START,
          SELECT_DOCUMENT_END,
          BLOCK_LEFT,
          BLOCK_RIGHT,
          BLOCK_UP,
          BLOCK_DOWN,
          FOLD,
          UNFOLD,
          TOGGLE_FOLD,
          FOLD_ALL,
          UNFOLD_ALL,
          SORT_LINES_ASCENDING,
          SORT_LINES_DESCENDING,
          SORT_LINES_IGNORE_CASE,
          REMOVE_DUPLICATE_LINES,
          TRIM_TRAILING_WHITESPACE,
          COMPLETE_WORD,
          SELECT_TO_MATCHING_BRACKET,
          GO_TO_MATCHING_BRACKET,
          TOGGLE_BOOKMARK,
          NEXT_BOOKMARK,
          PREVIOUS_BOOKMARK,
          CLEAR_BOOKMARKS,
          EDIT_BOOKMARK_COMMENT,
          DELETE_WORD_BACK,
          DELETE_WORD_FORWARD,
          TITLE_CASE,
          SWAP_CASE,
          TRANSPOSE,
          SELECT_ALL_OCCURRENCES,
          SKIP_OCCURRENCE,
          EXPAND_SELECTION,
          SELECT_LINE,
          UNDO_SELECTION,
          REDO_SELECTION ->
          edit(command);
      case BOOKMARKS_LIST -> bookmarkList.toggle();
      case QUICK_OPEN -> view.showQuickOpen(quickOpen(false, ""));
      case SWITCH_DOCUMENT -> view.showQuickOpen(quickOpen(true, ""));
      case GO_TO_SYMBOL -> view.showQuickOpen(quickOpen(false, "@"));
      case CLEAR_RECENT_FILES -> recentFiles.clear();
      case WORKSPACE_BROWSER -> workspace.toggle();
      case GO_BACK -> history.back();
      case GO_FORWARD -> history.forward();
      case COMPARE_DOCUMENTS -> compare.compareDocuments();
      case COMPARE_WITH_DISK -> compare.compareWithDisk();
      case SHOW_WHITESPACE -> documents.toggleMarks(false);
      case SHOW_LINE_ENDINGS -> documents.toggleMarks(true);
      case COMMAND_PALETTE -> view.showCommandPalette(new CommandPalette(this, set.keymap()));
      case TAB_WIDTH_2 -> documents.setTabWidth(2);
      case TAB_WIDTH_4 -> documents.setTabWidth(4);
      case TAB_WIDTH_8 -> documents.setTabWidth(8);
      case TAB_WIDTH_OTHER -> documents.askTabWidth();
      case INDENT_WITH_SPACES -> documents.toggleInsertSpaces();
      case GO_TO_LINE -> documents.goToLine();
      case READ_ONLY -> documents.toggleReadOnly();
      case WORD_WRAP -> documents.toggleWrap();
      case ZOOM_IN -> setFontSize(fontPoints() + 1);
      case ZOOM_OUT -> setFontSize(fontPoints() - 1);
      case ZOOM_RESET -> setFontSize(0);
      case PREFERENCES -> preferences.open();
      case THEME_LIGHT -> setTheme(ThemeMode.LIGHT);
      case THEME_DARK -> setTheme(ThemeMode.DARK);
      case THEME_SYSTEM -> setTheme(ThemeMode.SYSTEM);
      case SPLIT_HORIZONTAL -> documents.split(false);
      case SPLIT_VERTICAL -> documents.split(true);
      case UNSPLIT -> documents.unsplit();
      case FOCUS_OTHER_VIEW -> documents.focusOtherView();
      case RELOAD_GRAMMARS -> grammars.load();
      case FIND -> find.open(false);
      case FIND_NEXT -> find.findNext();
      case FIND_PREVIOUS -> find.findPrevious();
      case REPLACE -> find.open(true);
      case SET_WORKSPACE -> workspace.choose();
      case HYPERSEARCH -> search.showDialog();
      case NEXT_RESULT -> search.next(true);
      case PREVIOUS_RESULT -> search.next(false);
      case SEARCH_RESULTS -> search.toggleResults();
      case REPLACE_IN_RESULTS -> search.replaceInResults();
      case RESTORE_REPLACE_RUN -> replace.chooseRun();
      case OVERVIEW_STRIP -> documents.toggleOverview();
      case LINE_NUMBERS -> documents.toggleLineNumbers();
      case TOOLBAR -> toggleToolbar();
      case RIGHT_MARGIN -> toggleRightMargin();
      case INDENT_GUIDES -> aids.toggleIndentGuides();
      case LINE_CHANGES -> aids.toggleLineChanges();
      case WRAP_PARAGRAPH -> aids.wrapParagraph();
      case INSERT_DATE_TIME -> dateTime.choose();
      case DOCUMENT_STATISTICS -> statistics.open();
      case LINE_ENDING_CRLF -> documents.convertLineEndings(LineEnding.CRLF);
      case LINE_ENDING_LF -> documents.convertLineEndings(LineEnding.LF);
      case LINE_ENDING_CR -> documents.convertLineEndings(LineEnding.CR);
      case NEXT_TAB -> documents.selectNext(true);
      case PREVIOUS_TAB -> documents.selectNext(false);
      case CLOSE_OTHERS -> documents.closeOthers();
      case KEYBOARD_SHORTCUTS -> help.showShortcuts();
      case COMMAND_LINE_OPTIONS -> help.showCommandLine();
      case USER_GUIDE -> help.showGuide();
      case SHOW_LOG -> help.showLog();
      case DEBUG_LOG_FILE -> setDebugLogFile(!help.debugLogFile());
      case OPEN_LOG_FOLDER -> help.openLogFolder();
      case OPEN_SETTINGS_FOLDER -> help.openSettingsFolder();
      case REPORT_ISSUE -> help.reportIssue();
      case ABOUT -> help.showAbout();
    }
  }

  /**
   * Reloads the selected document's file decoded with a charset; see {@link
   * DocumentController#reopenWithEncoding}.
   *
   * @param charset the charset
   */
  public void reopenWithEncoding(Charset charset) {
    if (!quitting) {
      documents.reopenWithEncoding(charset);
    }
  }

  /** Reloads the selected document's file with detection; see {@link DocumentController}. */
  public void reopenWithDetection() {
    if (!quitting) {
      documents.reopenWithDetection();
    }
  }

  /**
   * Sets the charset the selected document's next save writes; see {@link
   * DocumentController#saveWithEncoding}.
   *
   * @param charset the charset
   */
  public void saveWithEncoding(Charset charset) {
    if (!quitting) {
      documents.saveWithEncoding(charset);
    }
  }

  /**
   * Reports whether the windows show the main toolbar.
   *
   * @return {@code ui.toolbar}
   */
  public boolean toolbarShown() {
    return settings.current().state().toolbar();
  }

  private void toggleToolbar() {
    Preferences current = settings.current();
    settings.set(current.withState(current.state().withToolbar(!toolbarShown())));
  }

  /**
   * Reports whether the editors show the right margin line.
   *
   * @return {@code ui.rightMargin}
   */
  public boolean rightMarginShown() {
    return settings.current().state().rightMargin();
  }

  private void toggleRightMargin() {
    Preferences current = settings.current();
    settings.set(current.withState(current.state().withRightMargin(!rightMarginShown())));
  }

  /** Returns {@code editor.rightMargin} while the line shows, else 0. */
  private int rightMarginColumn() {
    return rightMarginShown() ? settings.current().options().rightMargin() : 0;
  }

  /**
   * Opens the folder of a tab's file.
   *
   * @param tab the tab's input; a tab without a file does nothing
   */
  public void openContainingFolder(TabInput tab) {
    Path path = documents.pathOf(tab);
    Path folder = path == null ? null : path.toAbsolutePath().getParent();
    if (!quitting && folder != null) {
      help.openContainingFolder(folder);
    }
  }

  private void edit(CommandId command) {
    EditorInput editor = documents.activeInput();
    if (editor != null) {
      editor.execute(command);
    }
  }

  private void setFontSize(int points) {
    int bounded =
        points == 0 ? 0 : Math.max(Options.MIN_FONT_SIZE, Math.min(Options.MAX_FONT_SIZE, points));
    Preferences current = settings.current();
    settings.set(current.withOptions(current.options().withFontSize(bounded)));
  }

  /**
   * Opens or closes the debug log file and saves the choice in {@code log.debugFile}.
   *
   * @param on true to open it
   */
  private void setDebugLogFile(boolean on) {
    settings.set(settings.current().withDebugLogFile(on));
    help.setDebugLogFile(
        on,
        new Runnable() {
          @Override
          public void run() {
            view.documentStateChanged();
          }
        });
  }

  /** Closes every window's documents, asking about unsaved changes, then quits. */
  public void requestQuit() {
    set.requestQuit();
  }

  /**
   * Takes the session read at startup and starts recovery files, with no command line; a restore
   * that opens no tab opens an untitled document. See {@link WindowSet#startSession}.
   *
   * @param store the store the session was read from
   * @param loaded the session
   * @param restore true to restore and own the session
   */
  public void startSession(SessionStore store, Session loaded, final boolean restore) {
    set.startSession(
        this,
        store,
        loaded,
        restore,
        new Runnable() {
          @Override
          public void run() {
            if (restore && documents.count() == 0) {
              documents.newDocument();
            }
          }
        });
  }

  /**
   * Takes the session read at startup, then opens the command line's files and comparison.
   *
   * @param store the store the session was read from
   * @param loaded the session
   * @param restore true to restore and own the session
   * @param request the command line
   */
  public void startSession(
      SessionStore store, Session loaded, boolean restore, final StartupRequest request) {
    set.startSession(
        this,
        store,
        loaded,
        restore,
        new Runnable() {
          @Override
          public void run() {
            open(request, true);
          }
        });
  }

  /**
   * Sets {@code ui.theme} and applies the palette. When native controls show the theme only from
   * the next start, says so.
   *
   * @param mode the theme
   */
  public void setTheme(ThemeMode mode) {
    if (quitting) {
      return;
    }
    settings.set(settings.current().withTheme(mode));
  }

  /** Reads the license notices from the jar on a worker, then shows them; About's Licenses. */
  public void showLicenses() {
    help.showLicenses();
  }

  /**
   * Opens the user guide outside the window after its browser failed.
   *
   * @param guide the guide's copy
   */
  public void showGuideElsewhere(Path guide) {
    help.showGuideElsewhere(guide);
  }

  /**
   * Quits every window without asking; see {@link WindowSet#quit}. A second call does nothing. Call
   * on the UI thread.
   */
  public void quit() {
    set.quit();
  }

  // Multiple windows and the command line.

  /**
   * Returns the windows of the instance.
   *
   * @return the set this window belongs to
   */
  public WindowSet windowSet() {
    return set;
  }

  /** Closes this window, asking about its unsaved documents; the last window quits. */
  public void closeWindow() {
    set.requestClose(this);
  }

  /**
   * Opens the files and the comparison of a command line or a handoff.
   *
   * @param request the command line
   * @param untitled true to open an untitled document when the window stays without one
   */
  public void open(StartupRequest request, boolean untitled) {
    if (quitting) {
      return;
    }
    for (FileRequest file : request.files()) {
      documents.open(file);
    }
    if (untitled && request.files().isEmpty() && documents.count() == 0) {
      documents.newDocument();
    }
    if (request.diffLeft() != null) {
      compare.compareFiles(request.diffLeft(), request.diffRight());
    }
  }

  /**
   * Moves a tab to another window.
   *
   * @param tab the tab's input
   * @param target the receiving window
   * @param index the tab position there
   */
  public void moveTab(TabInput tab, ApplicationController target, int index) {
    Document document = documents.documentOf(tab);
    if (document != null) {
      set.moveDocument(this, document, target, new WindowSet.Placement(index, null, null));
    }
  }

  /**
   * Moves a tab to a new window.
   *
   * @param tab the tab's input
   * @param x the new window's left edge in display coordinates, or null for the window system
   * @param y its top edge, or null
   */
  public void moveTabToNewWindow(TabInput tab, Integer x, Integer y) {
    Document document = documents.documentOf(tab);
    if (document != null) {
      set.moveDocument(this, document, null, new WindowSet.Placement(0, x, y));
    }
  }

  private void moveActiveToNewWindow() {
    Document document = documents.activeDocument();
    if (document != null) {
      set.moveDocument(this, document, null, new WindowSet.Placement(0, null, null));
    }
  }

  /**
   * Lists the windows for the Window menu.
   *
   * @return each window's selected document, or its empty label, in creation order
   */
  public List<String> windowLabels() {
    List<String> labels = new ArrayList<String>();
    for (ApplicationController window : set.windows()) {
      String name = window.documents.activeName();
      labels.add(name == null ? UiText.WINDOW_EMPTY.text() : name);
    }
    return labels;
  }

  /**
   * Returns this window's place in {@link #windowLabels}.
   *
   * @return the index
   */
  public int windowIndex() {
    return set.windows().indexOf(this);
  }

  /**
   * Brings a window to the front.
   *
   * @param index its place in {@link #windowLabels}; out of range does nothing
   */
  public void showWindow(int index) {
    List<ApplicationController> windows = set.windows();
    if (index >= 0 && index < windows.size()) {
      windows.get(index).toFront();
    }
  }

  void toFront() {
    view.toFront();
  }

  /** Returns the window's view; for the session and the recovery files. */
  MainView view() {
    return view;
  }

  void showNotice(String notice) {
    view.showNotice(notice);
  }

  /** Records this window's normal bounds and maximized state unless the set saves none. */
  void rememberWindow() {
    if (set.rememberWindow()) {
      WindowGeometry.Request bounds = view.windowBounds();
      settings.record(
          settings
              .current()
              .withWindow(
                  new WindowState(
                      bounds.x(),
                      bounds.y(),
                      bounds.width(),
                      bounds.height(),
                      view.windowMaximized())));
    }
  }

  /** Stops the window's controllers and closes its documents without asking. */
  void stop() {
    quitting = true;
    settings.removeListener(settingsListener);
    find.dispose();
    search.dispose();
    replace.dispose();
    bookmarkList.dispose();
    workspace.dispose();
    history.dispose();
    compare.dispose();
    print.dispose();
    documents.dispose();
  }

  void hide() {
    view.hide();
  }

  void disposeView() {
    view.dispose();
  }
}
