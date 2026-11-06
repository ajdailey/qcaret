package io.github.ajdailey.qcaret.app.controller;

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
import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.app.view.EditorInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the main window's commands and owns the settings, the theme and the window's {@link
 * DocumentController}. Confined to the UI thread; file reads and settings writes run on {@code
 * qcaret-lifecycle-} workers. Edit commands from the menu go to the selected editor.
 *
 * <p>{@link CommandId#QUIT} first closes the documents, asking about each dirty one; a Cancel keeps
 * the window open. {@link #quit} itself asks nothing: the shutdown hook calls it.
 *
 * <p>Help commands run through {@link HelpController}. Menu check and enable state is read through
 * {@link CommandStates}. A zoom command steps {@code editor.fontSize} by one point.
 *
 * <p>{@link Settings} holds the settings and saves each change on a worker; {@link #apply} brings
 * every running controller and view in line with a change, wherever it came from. {@link #quit}
 * records the window's normal bounds and maximized state unless {@code rememberWindow} is false (a
 * {@code --window-*} option or {@code -m} was given), saves, closes the workers, then disposes the
 * view.
 */
public final class ApplicationController {
  private static final Logger LOG = LoggerFactory.getLogger(ApplicationController.class);

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
  private final GrammarLibrary grammars;
  private final PreferencesController preferences;
  private final Keymap defaultKeymap;
  private final boolean rememberWindow;
  private final List<Runnable> finishListeners = new ArrayList<Runnable>();
  private Keymap keymap;
  private boolean quitting;

  /**
   * Creates the controller.
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
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.settings = new Settings(config, workers, ui, preferences);
    this.theme = new ThemeController(system, preferences.theme());
    this.documents = new DocumentController(workers, ui, view, budget, LoadOptions.DEFAULTS);
    this.defaultKeymap = keymap;
    this.keymap = keymap.withOverrides(preferences.shortcuts());
    this.help =
        new HelpController(
            workers,
            ui,
            view,
            config.location(),
            this.keymap,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return quitting;
              }
            });
    this.rememberWindow = rememberWindow;
    this.find = new FindController(documents, workers, ui, settings);
    this.workspace = new WorkspaceController(view, workers, ui, documents, budget);
    this.history = new NavigationHistory(documents, workers, ui, view);
    this.recentFiles = new RecentFiles(documents, view);
    Options options = preferences.options();
    HyperSearch.Limits limits =
        HyperSearch.Limits.DEFAULTS.withMaxLineChars(options.searchMaxLineChars());
    this.search =
        new SearchController(documents, workspace, workers, ui, view, budget, limits, settings);
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
    this.grammars =
        new GrammarLibrary(workers, ui, view, config.location().directory().resolve("grammars"));
    documents.setGrammars(grammars);
    documents.setCompletionChars(options.completionChars());
    documents.setAutoClose(options.autoClose());
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
    this.preferences = new PreferencesController(settings, view, defaultKeymap, search);
    settings.addListener(
        new Settings.Listener() {
          @Override
          public void changed(Preferences before, Preferences after) {
            apply(before, after);
          }
        });
    logConflicts();
  }

  private void logConflicts() {
    for (String conflict : keymap.conflicts()) {
      LOG.warn("Key conflict: {}", LogText.oneLine(conflict));
    }
  }

  /** Brings the running controllers and the view in line with changed settings. */
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
    if (!after.shortcuts().equals(before.shortcuts())) {
      keymap = defaultKeymap.withOverrides(after.shortcuts());
      help.setKeymap(keymap);
      view.setKeymap(keymap);
      logConflicts();
    }
    if (after.theme() != before.theme() && !theme.setMode(after.theme())) {
      view.showInfo(UiText.THEME_RESTART_TITLE.text(), UiText.THEME_RESTART_MESSAGE.text());
    }
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
   * Returns the grammars.
   *
   * @return the grammar library
   */
  public GrammarLibrary grammars() {
    return grammars;
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
   * Connects Help > Show Log and the status bar's warning marker to the recent log lines, and Help
   * > Debug Log File to the log file. Call once, before the window opens.
   *
   * @param lines the recent lines
   * @param files the debug log file
   */
  public void setLogging(RecentLog lines, LogFiles files) {
    help.setLogging(lines, files);
    view.showWarningMarker(help.newWarnings());
  }

  /**
   * Returns the key bindings.
   *
   * @return the keymap
   */
  public Keymap keymap() {
    return keymap;
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
    return quitting;
  }

  /**
   * Adds a listener that runs on the lifecycle worker once the quit has saved and closed the
   * workers.
   *
   * @param listener the listener
   */
  public void addFinishListener(Runnable listener) {
    finishListeners.add(listener);
  }

  /** Checks the open files for changes by other programs; the window was activated. */
  public void windowActivated() {
    if (!quitting) {
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
      case CLOSE -> documents.closeActive();
      case CLOSE_ALL -> documents.closeAll(null);
      case QUIT -> requestQuit();
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
          EDIT_BOOKMARK_COMMENT ->
          edit(command);
      case BOOKMARKS_LIST -> bookmarkList.toggle();
      case QUICK_OPEN ->
          view.showQuickOpen(
              new QuickOpen(documents, workspace, workers, ui, view, false, recentFiles.paths()));
      case SWITCH_DOCUMENT ->
          view.showQuickOpen(
              new QuickOpen(documents, workspace, workers, ui, view, true, List.of()));
      case CLEAR_RECENT_FILES -> recentFiles.clear();
      case WORKSPACE_BROWSER -> workspace.toggle();
      case GO_BACK -> history.back();
      case GO_FORWARD -> history.forward();
      case COMPARE_DOCUMENTS -> compare.compareDocuments();
      case COMPARE_WITH_DISK -> compare.compareWithDisk();
      case SHOW_WHITESPACE -> documents.toggleMarks(false);
      case SHOW_LINE_ENDINGS -> documents.toggleMarks(true);
      case COMMAND_PALETTE -> view.showCommandPalette(new CommandPalette(this, keymap));
      case TAB_WIDTH_2 -> documents.setTabWidth(2);
      case TAB_WIDTH_4 -> documents.setTabWidth(4);
      case TAB_WIDTH_8 -> documents.setTabWidth(8);
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

  /** Closes the documents, asking about unsaved changes, then quits unless the user canceled. */
  public void requestQuit() {
    if (quitting) {
      return;
    }
    documents.closeAll(
        new Runnable() {
          @Override
          public void run() {
            quit();
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
   * Quits without asking: closes the documents, records and saves the window placement when
   * remembered, hides the window, closes the workers and disposes the window. A second call does
   * nothing. Call on the UI thread.
   */
  public void quit() {
    if (quitting) {
      return;
    }
    quitting = true;
    help.close();
    find.dispose();
    search.dispose();
    replace.dispose();
    bookmarkList.dispose();
    workspace.dispose();
    history.dispose();
    compare.dispose();
    documents.dispose();
    if (rememberWindow) {
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
    view.hide();
    Runnable finish =
        new Runnable() {
          @Override
          public void run() {
            settings.flush();
            if (!workers.close(Workers.CLOSE_BOUND)) {
              LOG.warn(
                  "Worker threads still running after {}: {}",
                  Workers.CLOSE_BOUND,
                  LogText.oneLine(workers.liveThreadNames()));
            }
            finished();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    view.dispose();
                  }
                });
          }
        };
    if (!workers.tryStart(Workers.Kind.LIFECYCLE, finish)) {
      finished();
      view.dispose();
    }
  }

  private void finished() {
    for (Runnable listener : finishListeners) {
      listener.run();
    }
  }
}
