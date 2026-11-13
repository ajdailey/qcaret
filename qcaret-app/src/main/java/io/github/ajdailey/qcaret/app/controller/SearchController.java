package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SearchToggles;
import io.github.ajdailey.qcaret.app.config.UiState;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/**
 * HyperSearch: the dialog, the runs, the retained result sets, the results dock's model and the
 * navigation to a result. Confined to the UI thread; each run is a {@link HyperSearch}. The dock's
 * labels and commands are in {@link ResultsDockModel}, the navigation in {@link ResultsNavigation}.
 *
 * <ul>
 *   <li>Sets are listed newest first. At most {@value #RETAINED} unpinned sets are kept; a new set
 *       drops the oldest unpinned one beyond that, stopping its search. Clear and Clear All stop
 *       and drop sets; each dropped set returns its heap to the {@link MemoryBudget}.
 *   <li>Rerun runs a finished set's request again while the set keeps showing its results; they are
 *       replaced when the rerun ends done or truncated, and kept with a notice when it is stopped
 *       (QLogg APP-2). Rerunning a running set stops it and starts again.
 *   <li>Opening a result: a document group maps its match to the current revision ({@link
 *       FileResult#rebase}); a file group opens its file, after checking its stamp and, when the
 *       stamp changed, its SHA-256 on a {@code qcaret-io-} worker. The match is selected only when
 *       the text there has the searched length and hash; otherwise it is marked stale, the caret
 *       goes to its line and a notice says so.
 *   <li>Next and Previous Result step through the matches of the set last navigated, or the newest
 *       set, wrapping at its ends.
 *   <li>The dialog's texts, directories and masks are kept in {@link Settings}, newest first, at
 *       most {@value UiState#MAX_HISTORY} each.
 *   <li>Rerun File searches one stale group's document or file again ({@link FileRerun}); a
 *       selection set reruns whole. Replace opens a finished set in the {@link ReplaceController}'s
 *       preview.
 * </ul>
 */
public final class SearchController implements ResultsInput {
  /** Unpinned sets kept. */
  static final int RETAINED = 10;

  /** Longest selection the dialog takes as its text. */
  static final int SEED_CHARS = 1000;

  private final DocumentController documents;
  private final WorkspaceController workspace;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final Settings settings;
  private HyperSearch.Limits limits;
  private final List<Entry> entries = new ArrayList<Entry>();
  private ReplaceController replace;
  private SearchForm last;
  private int resolving;
  private long nextId = 1;
  private boolean disposed;
  private final ResultsNavigation navigation;
  private final ResultsDockModel dock;

  /** One retained set, its running search and a rerun in progress. */
  static final class Entry {
    ResultSet set;
    HyperSearch run;
    HyperSearch rerun;
    boolean saved = true;

    Entry(ResultSet set) {
      this.set = set;
    }
  }

  /**
   * Creates the controller.
   *
   * @param documents the window's documents
   * @param workspace the window's workspace folder
   * @param workers runs the searches
   * @param ui receives the results
   * @param view the window
   * @param budget charged for the results
   * @param limits the search bounds
   * @param settings holds the toggles, the histories and the excluded directories
   */
  public SearchController(
      DocumentController documents,
      WorkspaceController workspace,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget,
      HyperSearch.Limits limits,
      Settings settings) {
    this.documents = documents;
    this.workspace = workspace;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
    this.limits = limits;
    this.settings = settings;
    this.navigation = new ResultsNavigation(this, entries, documents, workers, ui, view);
    this.dock =
        new ResultsDockModel(this, entries, navigation, documents, workers, ui, view, budget);
    documents.setCloseListener(
        new Consumer<Document>() {
          @Override
          public void accept(Document closed) {
            navigation.detach(closed);
          }
        });
  }

  /** Sets where Replace sends a set. */
  void setReplace(ReplaceController controller) {
    replace = controller;
  }

  // Dialog and runs.

  /**
   * Opens the HyperSearch dialog. A selection within one line, up to {@value #SEED_CHARS} chars,
   * becomes the text; a selection over several lines makes the selection the scope.
   */
  public void showDialog() {
    showDialog(null);
  }

  /**
   * Opens the HyperSearch dialog as {@link #showDialog()} does, with the scope set to a folder and
   * its subdirectories when one is given.
   *
   * @param folder the folder, or null
   */
  void showDialog(Path folder) {
    if (disposed) {
      return;
    }
    String text = last == null ? "" : last.text();
    SearchScope scope = last == null ? SearchScope.CURRENT_DOCUMENT : last.scope();
    EditorController editor = documents.activeEditor();
    if (editor != null) {
      Selection primary = editor.selections().selections().primary();
      if (!primary.isEmpty() && primary.end() - primary.start() <= SEED_CHARS) {
        String selected =
            editor.document().text().subSequence(primary.start(), primary.end()).toString();
        if (selected.indexOf('\n') < 0 && selected.indexOf('\r') < 0) {
          text = selected;
        } else {
          scope = SearchScope.SELECTION;
        }
      } else if (!primary.isEmpty()) {
        scope = SearchScope.SELECTION;
      }
    }
    UiState state = settings.current().state();
    String directory = state.directories().isEmpty() ? "" : state.directories().get(0);
    if (directory.isEmpty()) {
      Path active = documents.activePath();
      Path parent = active == null ? null : active.toAbsolutePath().getParent();
      directory =
          parent != null
              ? parent.toString()
              : workspace.root() == null ? "" : workspace.root().toString();
    }
    if (folder != null) {
      scope = SearchScope.TREE;
      directory = folder.toString();
    }
    SearchForm form =
        new SearchForm(
            text,
            state.search().regex(),
            state.search().caseSensitive(),
            state.search().wholeWord(),
            scope,
            directory,
            last == null ? "" : last.include(),
            last == null ? "" : last.exclude(),
            state.search().hiddenFiles(),
            workspace.root() == null ? "" : workspace.root().toString(),
            state.texts(),
            state.directories(),
            state.masks());
    view.showSearchDialog(
        form,
        new Consumer<SearchForm>() {
          @Override
          public void accept(SearchForm chosen) {
            run(chosen);
          }
        });
  }

  /**
   * Runs a search from the dialog's fields; an invalid field shows a message instead. A directory
   * or tree search starts once a {@code qcaret-io-} worker has resolved the folder's real path.
   *
   * @param form the fields
   */
  public void run(final SearchForm form) {
    if (disposed) {
      return;
    }
    last = form;
    Preferences current = settings.current();
    UiState state = current.state();
    settings.set(
        current.withState(
            state
                .withSearch(
                    new SearchToggles(
                        form.regex(), form.caseSensitive(), form.wholeWord(), form.hidden()))
                .withHistory(
                    UiState.remember(state.texts(), form.text()),
                    state.directories(),
                    UiState.remember(state.masks(), form.include()))));
    Path folder = null;
    if (form.scope() == SearchScope.WORKSPACE) {
      folder = workspace.root();
      if (folder == null) {
        view.showInfo(UiText.SEARCH_TITLE.text(), UiText.SEARCH_NO_WORKSPACE.text());
        return;
      }
    } else if (form.scope().folder()) {
      String entered = form.directory().strip();
      try {
        folder = entered.isEmpty() ? null : Path.of(entered).toAbsolutePath().normalize();
      } catch (InvalidPathException e) {
        folder = null;
      }
      if (folder == null) {
        view.showInfo(UiText.SEARCH_TITLE.text(), UiText.SEARCH_NO_DIRECTORY.format(entered));
        return;
      }
      final Path given = folder;
      resolving++;
      boolean started =
          workers.tryStart(
              Workers.Kind.IO,
              new Runnable() {
                @Override
                public void run() {
                  final Path real = WorkspaceController.realFolder(given);
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          resolving--;
                          if (!disposed) {
                            rememberDirectory(real.toString());
                            start(form, real);
                          }
                        }
                      });
                }
              });
      if (!started) {
        resolving--;
      }
      return;
    }
    start(form, folder);
  }

  /** Starts a search from the dialog's fields into a new set. */
  private void start(SearchForm form, Path folder) {
    start(
        new SearchRequest(
            form.text(),
            form.regex(),
            form.caseSensitive(),
            form.wholeWord(),
            form.scope(),
            folder,
            form.include(),
            form.exclude(),
            settings.current().options().excludedDirectories(),
            form.hidden()),
        null);
  }

  private void rememberDirectory(String directory) {
    Preferences current = settings.current();
    UiState state = current.state();
    settings.set(
        current.withState(
            state.withHistory(
                state.texts(), UiState.remember(state.directories(), directory), state.masks())));
  }

  /**
   * Drops the last run's fields, so the dialog opens without its masks, text or scope, and keeps
   * every result set out of the session; the sets stay in the dock for this run.
   */
  void forgetHistory() {
    last = null;
    for (Entry e : entries) {
      e.saved = false;
    }
  }

  /**
   * Sets the longest line a search reads ({@code search.maxLineChars}); the next search reads it.
   *
   * @param chars the longest line
   */
  void setMaxLineChars(int chars) {
    limits = limits.withMaxLineChars(chars);
  }

  /**
   * Starts a request into a new set, or as a rerun of an entry.
   *
   * @return false when the request was refused with a message
   */
  boolean start(SearchRequest request, Entry rerunOf) {
    if (request.text().isEmpty()) {
      view.showInfo(UiText.SEARCH_TITLE.text(), UiText.SEARCH_EMPTY.text());
      return false;
    }
    TextMatcher matcher;
    try {
      matcher = request.query().compile();
    } catch (PatternSyntaxException e) {
      view.showInfo(
          UiText.SEARCH_TITLE.text(),
          UiText.FIND_BAD_PATTERN.format(e.getDescription(), e.getIndex()));
      return false;
    }
    FileFilter filter = null;
    if (request.scope().folder()) {
      try {
        filter = request.filter();
      } catch (IllegalArgumentException e) {
        view.showInfo(UiText.SEARCH_TITLE.text(), UiText.SEARCH_BAD_MASK.format(e.getMessage()));
        return false;
      }
    }
    List<HyperSearch.Source> sources = sources(request, filter);
    if (sources == null) {
      return false;
    }
    Set<Path> open = new HashSet<Path>();
    for (Document d : documents.documentsAnywhere()) {
      Path path = documents.holderOf(d).pathOf(d);
      if (path != null) {
        open.add(path);
      }
    }
    HyperSearch.Folder folder =
        request.scope().folder()
            ? new HyperSearch.Folder(
                request.folder(), request.scope() != SearchScope.DIRECTORY, filter)
            : null;
    long id = rerunOf == null ? nextId++ : rerunOf.set.id();
    final ResultSet set = new ResultSet(id, request, System.currentTimeMillis());
    final Entry entry = rerunOf == null ? new Entry(set) : rerunOf;
    final HyperSearch[] started = new HyperSearch[1];
    started[0] =
        HyperSearch.start(
            set,
            matcher,
            sources,
            folder,
            open,
            limits,
            workers,
            ui,
            budget,
            new HyperSearch.Listener() {
              @Override
              public void changed() {
                if (!disposed && entry.run == started[0]) {
                  view.resultsChanged(false);
                  documents.refreshOverview();
                }
              }

              @Override
              public void finished() {
                ended(entry, started[0]);
              }
            });
    if (rerunOf == null) {
      entry.run = started[0];
      entries.add(0, entry);
      evict();
      if (!dock.resultsShown()) {
        dock.setShown(true);
      }
    } else {
      entry.rerun = started[0];
    }
    view.resultsChanged(true);
    return true;
  }

  /** Lists the open documents a request searches; null after a refusal with a message. */
  private List<HyperSearch.Source> sources(SearchRequest request, FileFilter filter) {
    List<HyperSearch.Source> sources = new ArrayList<HyperSearch.Source>();
    EditorController editor = documents.activeEditor();
    switch (request.scope()) {
      case CURRENT_DOCUMENT, SELECTION -> {
        if (editor == null) {
          view.showInfo(UiText.SEARCH_TITLE.text(), UiText.SEARCH_NO_DOCUMENT.text());
          return null;
        }
        int[] ranges = null;
        if (request.scope() == SearchScope.SELECTION) {
          ranges = selectionRanges(editor.selections().selections());
          if (ranges.length == 0) {
            view.showInfo(UiText.SEARCH_TITLE.text(), UiText.SEARCH_NO_SELECTION.text());
            return null;
          }
        }
        sources.add(source(editor.document(), ranges));
      }
      case OPEN_DOCUMENTS -> {
        for (Document d : documents.openDocuments()) {
          if (!documents.binary(d)) {
            sources.add(source(d, null));
          }
        }
      }
      case WORKSPACE, DIRECTORY, TREE -> {
        for (Document d : documents.documentsAnywhere()) {
          DocumentController holder = documents.holderOf(d);
          Path path = holder.pathOf(d);
          if (path != null
              && !holder.binary(d)
              && inFolder(
                  path, request.folder(), request.scope() != SearchScope.DIRECTORY, filter)) {
            sources.add(source(d, null));
          }
        }
      }
    }
    return sources;
  }

  private HyperSearch.Source source(Document document, int[] ranges) {
    DocumentController holder = documents.holderOf(document);
    return new HyperSearch.Source(
        document, holder.pathOf(document), holder.nameOf(document), ranges);
  }

  /** The non-empty selections as ascending offset pairs. */
  static int[] selectionRanges(SelectionSet set) {
    List<Selection> list = set.selections();
    int[] ranges = new int[list.size() * 2];
    int n = 0;
    for (Selection s : list) {
      if (!s.isEmpty()) {
        ranges[n++] = s.start();
        ranges[n++] = s.end();
      }
    }
    return Arrays.copyOf(ranges, n);
  }

  /**
   * Reports whether a folder search would read a file: inside the folder (directly, unless
   * recursive), its name passing the masks, no directory between them excluded or hidden. Both
   * paths are real paths.
   */
  static boolean inFolder(Path file, Path folder, boolean recursive, FileFilter filter) {
    if (!file.startsWith(folder) || file.equals(folder)) {
      return false;
    }
    Path relative = folder.relativize(file);
    if (!recursive && relative.getNameCount() != 1) {
      return false;
    }
    for (int i = 0; i < relative.getNameCount(); i++) {
      Path name = relative.getName(i);
      boolean last = i == relative.getNameCount() - 1;
      if (!filter.hidden() && name.toString().startsWith(".")) {
        return false;
      }
      if (last ? !filter.acceptsFile(name) : !filter.acceptsDirectory(name)) {
        return false;
      }
    }
    return true;
  }

  /** Drops the oldest unpinned sets beyond {@link #RETAINED}. */
  void evict() {
    int unpinned = 0;
    for (int i = 0; i < entries.size(); i++) {
      Entry e = entries.get(i);
      if (!e.set.pinned()) {
        unpinned++;
        if (unpinned > RETAINED) {
          drop(entries.remove(i));
          i--;
        }
      }
    }
  }

  void drop(Entry e) {
    if (e.run != null) {
      e.run.cancel();
    }
    if (e.rerun != null) {
      e.rerun.cancel();
      e.rerun.set().release();
    }
    e.set.release();
    navigation.forget(e.set.id());
  }

  /**
   * A search ended: a first run shows its state, a rerun replaces or keeps the set's results, and a
   * set no entry holds returns its heap, groups that arrived after it was dropped included.
   */
  private void ended(Entry entry, HyperSearch search) {
    ResultSet result = search.set();
    boolean retained = !disposed && entries.contains(entry);
    if (retained && entry.rerun == search) {
      entry.rerun = null;
      if (result.state() == ResultSet.State.DONE || result.state() == ResultSet.State.TRUNCATED) {
        result.setPinned(entry.set.pinned());
        entry.set.release();
        entry.set = result;
        navigation.restart(result.id());
      } else {
        result.release();
        view.showNotice(UiText.SEARCH_RERUN_KEPT.text());
      }
    } else if (retained && entry.run == search) {
      entry.run = null;
    } else if (!retained || result != entry.set) {
      result.release();
    }
    if (!disposed) {
      view.resultsChanged(true);
      documents.refreshOverview();
      view.documentStateChanged();
    }
  }

  /** Stops every search and returns every set's heap. */
  public void dispose() {
    disposed = true;
    navigation.dispose();
    for (Entry e : entries) {
      drop(e);
    }
    entries.clear();
  }

  /** Reports whether the controller is disposed. */
  boolean disposed() {
    return disposed;
  }

  /** Reports whether a folder is being resolved for a search. */
  boolean resolving() {
    return resolving > 0;
  }

  /** Returns a new set id. */
  long newId() {
    return nextId++;
  }

  /** Returns the search bounds. */
  HyperSearch.Limits limits() {
    return limits;
  }

  /** Returns where Replace sends a set, or null. */
  ReplaceController replaceController() {
    return replace;
  }

  /** Returns a set's file group. */
  FileResult group(int set, int file) {
    return entries.get(set).set.files().get(file);
  }

  // Results dock: ResultsDockModel.

  /**
   * Reports whether the results dock shows.
   *
   * @return true while shown
   */
  boolean resultsShown() {
    return dock.resultsShown();
  }

  /** Shows the results dock, or hides it. */
  public void toggleResults() {
    dock.toggleResults();
  }

  /**
   * Returns the retained requests for the session, newest first; a text over {@link
   * UiState#MAX_ENTRY_CHARS} chars and a set Clear History reached are left out.
   *
   * @return the requests
   */
  List<Session.Search> retained() {
    return dock.retained();
  }

  /**
   * Adds a session's requests as sets without results, which Rerun fills, and shows the dock.
   *
   * @param searches the requests, newest first
   * @param show true to show the dock
   */
  void restore(List<Session.Search> searches, boolean show) {
    dock.restore(searches, show);
  }

  /**
   * Reports whether a set holds a match, for Next and Previous Result.
   *
   * @return true when any set has matches
   */
  boolean hasResults() {
    return dock.hasResults();
  }

  /**
   * Returns the sets, newest first; for tests.
   *
   * @return the sets
   */
  List<ResultSet> sets() {
    return dock.sets();
  }

  /**
   * Reports whether a search or a rerun runs, or a folder is being resolved for one.
   *
   * @return true while any runs
   */
  boolean running() {
    return dock.running();
  }

  @Override
  public int setCount() {
    return entries.size();
  }

  @Override
  public long setId(int set) {
    return entries.get(set).set.id();
  }

  @Override
  public ResultLabel set(int set) {
    return dock.set(set);
  }

  @Override
  public int fileCount(int set) {
    return entries.get(set).set.files().size();
  }

  @Override
  public ResultLabel file(int set, int file) {
    return dock.file(set, file);
  }

  @Override
  public int matchCount(int set, int file) {
    return group(set, file).matchCount();
  }

  @Override
  public ResultLabel match(int set, int file, int match) {
    return dock.match(set, file, match);
  }

  @Override
  public void expanded(int set, int file) {
    dock.expanded(set, file);
  }

  /**
   * Runs a set's request again. A running search or rerun of the set stops first; the set keeps its
   * results until the new run ends.
   */
  @Override
  public void rerun(int set) {
    dock.rerun(set);
  }

  @Override
  public void rerunFile(int set, int file) {
    dock.rerunFile(set, file);
  }

  @Override
  public void replace(int set) {
    dock.replace(set);
  }

  /** Opens the replace preview for the set last navigated, or else the newest set with matches. */
  public void replaceInResults() {
    dock.replaceInResults();
  }

  @Override
  public void stop(int set) {
    dock.stop(set);
  }

  @Override
  public void togglePin(int set) {
    dock.togglePin(set);
  }

  @Override
  public boolean pinned(int set) {
    return entries.get(set).set.pinned();
  }

  @Override
  public boolean running(int set) {
    Entry e = entries.get(set);
    return e.run != null || e.rerun != null;
  }

  @Override
  public void clear(int set) {
    dock.clear(set);
  }

  @Override
  public void clearAll() {
    dock.clearAll();
  }

  @Override
  public void hide() {
    dock.hide();
  }

  // Navigation: ResultsNavigation.

  /**
   * Opens the next match, or the previous one, of the set last navigated or else the newest set
   * with matches, wrapping at its ends.
   *
   * @param forward true for the next match
   */
  public void next(boolean forward) {
    navigation.next(forward);
  }

  @Override
  public void open(int set, int file, int match) {
    navigation.open(set, file, match);
  }

  /** A document closed in another window: its groups keep their offsets and open the file. */
  void documentClosed(Document closed) {
    navigation.detach(closed);
  }

  /**
   * Returns the starts of the matches in a document of the set last navigated, or else the newest
   * set, in the document's current revision.
   *
   * @param document the document
   * @return model offsets
   */
  int[] offsets(Document document) {
    return navigation.offsets(document);
  }
}
