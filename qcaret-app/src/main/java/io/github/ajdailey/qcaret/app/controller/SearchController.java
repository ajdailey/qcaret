package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.search.FileRerun;
import io.github.ajdailey.qcaret.app.search.FileResult;
import io.github.ajdailey.qcaret.app.search.HyperSearch;
import io.github.ajdailey.qcaret.app.search.ReplaceRun;
import io.github.ajdailey.qcaret.app.search.ResultSet;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.ResultLabel;
import io.github.ajdailey.qcaret.app.view.ResultsInput;
import io.github.ajdailey.qcaret.app.view.SearchForm;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import io.github.ajdailey.qcaret.core.search.TextSearch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/**
 * HyperSearch: the dialog, the runs, the retained result sets, the results dock's model and the
 * navigation to a result. Confined to the UI thread; each run is a {@link HyperSearch}.
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
 *   <li>The dialog's texts, directories and masks are remembered for the run, newest first, at most
 *       {@value #HISTORY} each.
 *   <li>Rerun File searches one stale group's document or file again ({@link FileRerun}); a
 *       selection set reruns whole. Replace opens a finished set in the {@link ReplaceController}'s
 *       preview.
 * </ul>
 */
public final class SearchController implements ResultsInput {
  /** Unpinned sets kept. */
  static final int RETAINED = 10;

  /** Entries per dialog history. */
  static final int HISTORY = 20;

  /** Longest selection the dialog takes as its text. */
  static final int SEED_CHARS = 1000;

  private final DocumentController documents;
  private final WorkspaceController workspace;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final HyperSearch.Limits limits;
  private final List<Entry> entries = new ArrayList<Entry>();
  private final List<String> texts = new ArrayList<String>();
  private final List<String> directories = new ArrayList<String>();
  private final List<String> masks = new ArrayList<String>();
  private ReplaceController replace;
  private SearchForm last;
  private int resolving;
  private long nextId = 1;
  private boolean shown;
  private boolean disposed;
  private long cursorSet = -1;
  private int cursorFile;
  private int cursorMatch = -1;

  /** One retained set, its running search and a rerun in progress. */
  private static final class Entry {
    ResultSet set;
    HyperSearch run;
    HyperSearch rerun;

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
   */
  public SearchController(
      DocumentController documents,
      WorkspaceController workspace,
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget,
      HyperSearch.Limits limits) {
    this.documents = documents;
    this.workspace = workspace;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
    this.limits = limits;
    documents.setCloseListener(
        new Consumer<Document>() {
          @Override
          public void accept(Document closed) {
            detach(closed);
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
    String directory = directories.isEmpty() ? "" : directories.get(0);
    if (directory.isEmpty()) {
      Path active = documents.activePath();
      Path parent = active == null ? null : active.toAbsolutePath().getParent();
      directory =
          parent != null
              ? parent.toString()
              : workspace.root() == null ? "" : workspace.root().toString();
    }
    SearchForm form =
        new SearchForm(
            text,
            last != null && last.regex(),
            last != null && last.caseSensitive(),
            last != null && last.wholeWord(),
            scope,
            directory,
            last == null ? "" : last.include(),
            last == null ? "" : last.exclude(),
            last != null && last.hidden(),
            workspace.root() == null ? "" : workspace.root().toString(),
            texts,
            directories,
            masks);
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
    remember(texts, form.text());
    remember(masks, form.include());
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
                            remember(directories, real.toString());
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
            FileFilter.DEFAULT_EXCLUDED_DIRECTORIES,
            form.hidden()),
        null);
  }

  private static void remember(List<String> history, String value) {
    if (value.isBlank()) {
      return;
    }
    history.remove(value);
    history.add(0, value);
    if (history.size() > HISTORY) {
      history.remove(history.size() - 1);
    }
  }

  /**
   * Starts a request into a new set, or as a rerun of an entry.
   *
   * @return false when the request was refused with a message
   */
  private boolean start(SearchRequest request, Entry rerunOf) {
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
    for (Document d : documents.openDocuments()) {
      Path path = documents.pathOf(d);
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
      if (!shown) {
        setShown(true);
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
        for (Document d : documents.openDocuments()) {
          Path path = documents.pathOf(d);
          if (path != null
              && !documents.binary(d)
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
    return new HyperSearch.Source(
        document, documents.pathOf(document), documents.nameOf(document), ranges);
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
  private void evict() {
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

  private void drop(Entry e) {
    if (e.run != null) {
      e.run.cancel();
    }
    if (e.rerun != null) {
      e.rerun.cancel();
      e.rerun.set().release();
    }
    e.set.release();
    if (cursorSet == e.set.id()) {
      cursorSet = -1;
    }
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
        if (cursorSet == result.id()) {
          cursorMatch = -1;
        }
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
    for (Entry e : entries) {
      drop(e);
    }
    entries.clear();
  }

  // Results dock.

  /**
   * Reports whether the results dock shows.
   *
   * @return true while shown
   */
  boolean resultsShown() {
    return shown;
  }

  /** Shows the results dock, or hides it. */
  public void toggleResults() {
    setShown(!shown);
  }

  private void setShown(boolean value) {
    shown = value;
    view.showResults(this, value);
    view.documentStateChanged();
  }

  /**
   * Reports whether a set holds a match, for Next and Previous Result.
   *
   * @return true when any set has matches
   */
  boolean hasResults() {
    for (Entry e : entries) {
      if (e.set.matches() > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the sets, newest first; for tests.
   *
   * @return the sets
   */
  List<ResultSet> sets() {
    List<ResultSet> list = new ArrayList<ResultSet>();
    for (Entry e : entries) {
      list.add(e.set);
    }
    return list;
  }

  /**
   * Reports whether a search or a rerun runs, or a folder is being resolved for one.
   *
   * @return true while any runs
   */
  boolean running() {
    if (resolving > 0) {
      return true;
    }
    for (Entry e : entries) {
      if (e.run != null || e.rerun != null) {
        return true;
      }
    }
    return false;
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
    Entry e = entries.get(set);
    ResultSet r = e.set;
    SearchRequest q = r.request();
    String scope = q.scope().label() + (q.folder() == null ? "" : " " + q.folder());
    StringBuilder text =
        new StringBuilder(UiText.SEARCH_SET.format(q.text(), scope, r.matches(), r.files().size()));
    List<String> notes = new ArrayList<String>();
    switch (r.state()) {
      case RUNNING ->
          notes.add(
              UiText.SEARCH_RUNNING.format(
                  r.searched(), r.total() < 0 ? "?" : String.valueOf(r.total())));
      case CANCELED -> notes.add(UiText.SEARCH_CANCELED.text());
      case TRUNCATED ->
          notes.add(
              r.noMemory()
                  ? UiText.SEARCH_NO_MEMORY.text()
                  : UiText.SEARCH_TRUNCATED.format(r.matches()));
      case DONE -> {
        // No note.
      }
    }
    if (e.rerun != null) {
      notes.add(UiText.SEARCH_RERUNNING.text());
    }
    List<String> skipped = new ArrayList<String>();
    count(skipped, UiText.SEARCH_SKIPPED_BINARY, r.binary());
    count(skipped, UiText.SEARCH_SKIPPED_LARGE, r.tooLarge());
    count(skipped, UiText.SEARCH_SKIPPED_UNREADABLE, r.unreadable());
    count(skipped, UiText.SEARCH_SKIPPED_LINES, r.skippedLines());
    count(skipped, UiText.SEARCH_SKIPPED_COMPLEX, r.tooComplex());
    if (!skipped.isEmpty()) {
      notes.add(UiText.SEARCH_SKIPPED.format(String.join(", ", skipped)));
    }
    if (r.pinned()) {
      notes.add(UiText.SEARCH_PINNED.text());
    }
    for (String note : notes) {
      text.append("; ").append(note);
    }
    return ResultLabel.plain(text.toString(), false);
  }

  private static void count(List<String> parts, UiText label, int count) {
    if (count > 0) {
      parts.add(label.format(count));
    }
  }

  @Override
  public int fileCount(int set) {
    return entries.get(set).set.files().size();
  }

  @Override
  public ResultLabel file(int set, int file) {
    FileResult f = group(set, file);
    f.rebase();
    Path parent = f.path() == null ? null : f.path().getParent();
    StringBuilder text =
        new StringBuilder(
            UiText.SEARCH_FILE.format(
                f.name(), parent == null ? "" : parent.toString(), f.matchCount()));
    if (f.tooComplexLine() >= 0) {
      text.append("; ").append(UiText.SEARCH_FILE_COMPLEX.format(f.tooComplexLine() + 1));
    }
    if (f.skippedLines() > 0) {
      text.append("; ").append(UiText.SEARCH_FILE_LINES.format(f.skippedLines()));
    }
    if (f.stale()) {
      text.append("; ").append(UiText.SEARCH_CHANGED.text());
    }
    return ResultLabel.plain(text.toString(), f.stale());
  }

  @Override
  public int matchCount(int set, int file) {
    return group(set, file).matchCount();
  }

  @Override
  public ResultLabel match(int set, int file, int match) {
    FileResult f = group(set, file);
    f.rebase();
    TextSearch.Hit hit = f.hit(match);
    String prefix = (f.line(match) + 1) + ": ";
    return new ResultLabel(
        prefix + hit.snippet(),
        prefix.length() + hit.snippetStart(),
        prefix.length() + hit.snippetEnd(),
        f.stale(match));
  }

  private FileResult group(int set, int file) {
    return entries.get(set).set.files().get(file);
  }

  @Override
  public void expanded(int set, int file) {
    FileResult f = group(set, file);
    if (f.document() == null && f.stamp() != null && !f.stale()) {
      check(f, null);
    }
  }

  /**
   * Runs a set's request again. A running search or rerun of the set stops first; the set keeps its
   * results until the new run ends.
   */
  @Override
  public void rerun(int set) {
    Entry e = entries.get(set);
    if (e.run != null) {
      e.run.cancel();
    }
    if (e.rerun != null) {
      e.rerun.cancel();
    }
    start(e.set.request(), e);
  }

  @Override
  public void rerunFile(final int set, int file) {
    final Entry e = entries.get(set);
    if (e.run != null || e.rerun != null) {
      view.showNotice(UiText.SEARCH_NOT_FINISHED.text());
      return;
    }
    if (e.set.request().scope() == SearchScope.SELECTION) {
      rerun(set);
      return;
    }
    final FileResult group = e.set.files().get(file);
    Document document = group.document();
    if (document != null && !documents.isOpen(document)) {
      group.detach();
      document = null;
    }
    if (document == null && group.path() != null) {
      document = documents.documentAt(group.path());
    }
    if (document == null && group.path() == null) {
      view.showNotice(UiText.SEARCH_CLOSED.text());
      return;
    }
    final String name = document == null ? group.name() : documents.nameOf(document);
    final ResultSet results = e.set;
    FileRerun.start(
        results,
        group,
        results.request().query().compile(),
        document,
        group.path(),
        name,
        limits,
        workers,
        ui,
        budget,
        new FileRerun.Listener() {
          @Override
          public void finished(int matches, FileSearch.Skip skip) {
            if (disposed) {
              return;
            }
            if (skip != null) {
              view.showNotice(
                  UiText.SEARCH_RERUN_FILE_SKIPPED.format(
                      name, ReplaceController.reasonText(ReplaceRun.Reason.valueOf(skip.name()))));
            } else if (matches == 0) {
              view.showNotice(UiText.SEARCH_RERUN_FILE_EMPTY.format(name));
            }
            if (cursorSet == results.id()) {
              cursorMatch = -1;
            }
            view.resultsChanged(true);
            documents.refreshOverview();
            view.documentStateChanged();
          }
        });
  }

  @Override
  public void replace(int set) {
    Entry e = entries.get(set);
    if (e.run != null || e.rerun != null) {
      view.showNotice(UiText.SEARCH_NOT_FINISHED.text());
      return;
    }
    if (replace != null) {
      replace.preview(e.set);
    }
  }

  /** Opens the replace preview for the set last navigated, or else the newest set with matches. */
  public void replaceInResults() {
    int set = indexOf(cursorSet);
    if (set < 0 || entries.get(set).set.matches() == 0) {
      set = -1;
      for (int i = 0; i < entries.size() && set < 0; i++) {
        if (entries.get(i).set.matches() > 0) {
          set = i;
        }
      }
    }
    if (set < 0) {
      view.showNotice(UiText.SEARCH_NO_RESULTS.text());
      return;
    }
    replace(set);
  }

  @Override
  public void stop(int set) {
    Entry e = entries.get(set);
    if (e.run != null) {
      e.run.cancel();
    }
    if (e.rerun != null) {
      e.rerun.cancel();
    }
  }

  @Override
  public void togglePin(int set) {
    ResultSet r = entries.get(set).set;
    r.setPinned(!r.pinned());
    evict();
    view.resultsChanged(true);
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
    drop(entries.remove(set));
    view.resultsChanged(true);
    documents.refreshOverview();
    view.documentStateChanged();
  }

  @Override
  public void clearAll() {
    for (Entry e : entries) {
      drop(e);
    }
    entries.clear();
    view.resultsChanged(true);
    documents.refreshOverview();
    view.documentStateChanged();
  }

  @Override
  public void hide() {
    if (shown) {
      setShown(false);
    }
  }

  // Navigation.

  /**
   * Opens the next match, or the previous one, of the set last navigated or else the newest set
   * with matches, wrapping at its ends.
   *
   * @param forward true for the next match
   */
  public void next(boolean forward) {
    int set = indexOf(cursorSet);
    if (set < 0 || entries.get(set).set.matches() == 0) {
      set = -1;
      for (int i = 0; i < entries.size() && set < 0; i++) {
        if (entries.get(i).set.matches() > 0) {
          set = i;
        }
      }
      if (set < 0) {
        view.showNotice(UiText.SEARCH_NO_RESULTS.text());
        return;
      }
      cursorSet = entries.get(set).set.id();
      cursorFile = 0;
      cursorMatch = -1;
    }
    List<FileResult> files = entries.get(set).set.files();
    int file = Math.min(cursorFile, files.size() - 1);
    int match = cursorMatch;
    if (match < 0) {
      file = forward ? 0 : files.size() - 1;
      while (files.get(file).matchCount() == 0) {
        file = (file + (forward ? 1 : files.size() - 1)) % files.size();
      }
      match = forward ? 0 : files.get(file).matchCount() - 1;
    } else {
      match += forward ? 1 : -1;
      while (match < 0 || match >= files.get(file).matchCount()) {
        file = (file + (forward ? 1 : files.size() - 1)) % files.size();
        match = forward ? 0 : files.get(file).matchCount() - 1;
      }
    }
    open(set, file, match);
  }

  private int indexOf(long id) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).set.id() == id) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public void open(int set, final int file, final int match) {
    final ResultSet r = entries.get(set).set;
    final FileResult f = r.files().get(file);
    cursorSet = r.id();
    cursorFile = file;
    cursorMatch = match;
    view.revealResult(set, file, match);
    Document document = f.document();
    if (document != null) {
      if (!documents.isOpen(document)) {
        f.detach();
      } else {
        f.rebase();
        select(documents.show(document), f, match);
        return;
      }
    }
    Path path = f.path();
    if (path == null) {
      view.showNotice(UiText.SEARCH_CLOSED.text());
      return;
    }
    Document opened = documents.documentAt(path);
    if (opened != null) {
      select(documents.show(opened), f, match);
      return;
    }
    if (f.stamp() != null && !f.stale()) {
      check(
          f,
          new Runnable() {
            @Override
            public void run() {
              openFile(f, match);
            }
          });
    } else {
      openFile(f, match);
    }
  }

  private void openFile(final FileResult f, final int match) {
    documents.open(
        f.path(),
        new Consumer<EditorController>() {
          @Override
          public void accept(EditorController editor) {
            select(editor, f, match);
          }
        });
  }

  /**
   * Selects a match in an editor when the text there is the text searched; otherwise marks it stale
   * and moves the caret to its line.
   */
  private void select(EditorController editor, FileResult f, int match) {
    if (editor == null || disposed) {
      return;
    }
    editor.jumping();
    Document document = editor.document();
    TextSnapshot text = document.text();
    int start = f.start(match);
    int end = f.end(match);
    boolean same =
        !f.stale(match)
            && end <= text.length()
            && text.subSequence(start, end).toString().hashCode() == f.hit(match).hash();
    if (same) {
      if (f.document() == null) {
        f.attach(document);
      }
      editor.selections().set(SelectionSet.of(List.of(Selection.of(start, end)), 0));
    } else {
      f.markStale(match);
      int line = Math.min(f.line(match), text.lineCount() - 1);
      editor.selections().set(SelectionSet.caret(text.offsetOfLine(line)));
      view.showNotice(UiText.SEARCH_STALE.format(line + 1));
      view.resultsChanged(true);
    }
    if (editor.view() != null) {
      editor.view().showCaret();
    }
    documents.refreshOverview();
  }

  /**
   * Checks a file group's file on a {@code qcaret-io-} worker: a changed stamp whose bytes have
   * another SHA-256 marks the group stale. Then runs {@code then} on the UI thread, if given.
   */
  private void check(final FileResult f, final Runnable then) {
    final Path path = f.path();
    final FileStamp stamp = f.stamp();
    final byte[] digest = f.digest();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final boolean changed = changed(path, stamp, digest);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        checked(f, changed, then);
                      }
                    });
              }
            });
    if (!started && then != null) {
      then.run();
    }
  }

  private void checked(FileResult f, boolean changed, Runnable then) {
    if (disposed) {
      return;
    }
    if (changed && !f.stale()) {
      f.markStale();
      view.resultsChanged(true);
    }
    if (then != null) {
      then.run();
    }
  }

  /** Reports whether a file differs from the bytes searched. */
  @WorkerThread
  static boolean changed(Path path, FileStamp stamp, byte[] digest) {
    try {
      FileStamp now = FileStamp.read(path);
      if (stamp.equals(now)) {
        return false;
      }
      if (now == null || digest == null) {
        return true;
      }
      return !MessageDigest.isEqual(digest, FileSearch.digest(Files.readAllBytes(path)));
    } catch (IOException e) {
      return true;
    }
  }

  /** A document closed: its groups keep their offsets and open the file from then on. */
  private void detach(Document closed) {
    for (Entry e : entries) {
      for (FileResult f : e.set.files()) {
        if (f.document() == closed) {
          f.detach();
        }
      }
    }
  }

  /**
   * Returns the starts of the matches in a document of the set last navigated, or else the newest
   * set, in the document's current revision.
   *
   * @param document the document
   * @return model offsets
   */
  int[] offsets(Document document) {
    int set = indexOf(cursorSet);
    if (set < 0) {
      set = entries.isEmpty() ? -1 : 0;
    }
    if (set < 0) {
      return new int[0];
    }
    int[] out = new int[0];
    for (FileResult f : entries.get(set).set.files()) {
      if (f.document() == document && !f.stale()) {
        f.rebase();
        int at = out.length;
        out = Arrays.copyOf(out, at + f.matchCount());
        for (int i = 0; i < f.matchCount(); i++) {
          out[at + i] = f.start(i);
        }
      }
    }
    return out;
  }
}
