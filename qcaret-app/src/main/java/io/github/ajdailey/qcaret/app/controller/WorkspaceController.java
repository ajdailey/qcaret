package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.workspace.DirectoryListing;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import io.github.ajdailey.qcaret.core.workspace.ListingOptions;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * The window's workspace folder, set with File > Set Workspace Folder and kept for the run, with
 * its browser and file index. HyperSearch's workspace scope searches its tree. Confined to the UI
 * thread.
 *
 * <p>The browser shows the workspace folder, or a folder browsed to through {@link WorkspaceBrowse}
 * without changing the workspace. It lists a directory on a {@code qcaret-io-} worker the first
 * time it expands ({@link DirectoryListing}, {@code workspace.excludedDirectories}, the filter,
 * hidden entries and the sort), with the rows' Size and Modified texts ({@link BrowserRows}), and
 * shows {@value #PAGE} entries at a time behind a "more" row. Refresh, and a filter, hidden or sort
 * change, list every listed directory again, keeping the old rows until the new ones arrive;
 * Refresh also rebuilds the index. A new shown folder drops every listing, so a listing still
 * running is canceled and its result dropped. The row menu over the selected rows runs through
 * {@link WorkspaceSelection}, its file operations in {@link WorkspaceFiles}; after one, or a
 * Refresh, every other window's browser lists the directories it shows again ({@link
 * BrowserWindows}). A rename of the workspace folder or a parent moves the workspace in every
 * window ({@link #follow}); a delete of either clears it ({@link #clearUnder}).
 *
 * <p>The file index ({@link FileIndex}, at most {@link FileIndex#MAX_FILES} files, charged to the
 * {@link MemoryBudget}) is built on a {@code qcaret-search-} worker whenever the folder changes and
 * on Refresh; a new build cancels the running one. A folder change drops the index at once; Refresh
 * keeps it until the new one arrives. A dropped, replaced or stale index returns its charge, and
 * {@link #dispose} cancels the build and returns the charge.
 */
public final class WorkspaceController implements WorkspaceInput {
  /** Entries a directory shows at once; the "more" row adds as many again. */
  public static final int PAGE = 500;

  /** Extensions Windows runs rather than opens. */
  static final List<String> WINDOWS_EXECUTABLE =
      List.of(
          ".bat", ".cmd", ".com", ".cpl", ".exe", ".hta", ".js", ".jse", ".lnk", ".msc", ".msi",
          ".pif", ".ps1", ".reg", ".scr", ".url", ".vbe", ".vbs", ".wsf", ".wsh");

  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final DocumentController documents;
  private final MemoryBudget budget;
  private final Map<Path, Listing> listings = new HashMap<Path, Listing>();

  /** The entries the browser and the index leave out ({@code workspace.excludedDirectories}). */
  private WalkRules rules = WalkRules.of(FileFilter.DEFAULT_EXCLUDED_DIRECTORIES);

  private final List<Runnable> indexListeners = new ArrayList<Runnable>();
  private Path root;
  private boolean shown;
  private boolean disposed;
  private FileIndex index;
  private CancellationToken indexing;
  private Path revealing;
  private WorkspaceFiles files;
  private WorkspaceBrowse browse;
  private BrowserWindows peers;
  private final WorkspaceFollow following;
  private final WorkspaceSelection selection;

  /** The folder the browser shows instead of the workspace folder; null for the workspace. */
  private Path browsed;

  /** Runs on the worker before a directory is read; a test seam, null otherwise. */
  private volatile Runnable beforeList;

  /** One directory's listing as the browser holds it. */
  private static final class Listing {
    DirectoryListing entries;
    List<WorkspaceNode> rows;
    String error;
    int shown = PAGE;
    CancellationToken reading;
  }

  /**
   * Creates the controller without a folder.
   *
   * @param view asks for the folder and shows the browser
   * @param workers lists directories and builds the index
   * @param ui receives worker results
   * @param documents opens files and names the selected one
   * @param budget charged for the index
   */
  public WorkspaceController(
      MainView view,
      Workers workers,
      UiDispatcher ui,
      DocumentController documents,
      MemoryBudget budget) {
    this.view = view;
    this.workers = workers;
    this.ui = ui;
    this.documents = documents;
    this.budget = budget;
    this.following = new WorkspaceFollow(this, view);
    this.selection = new WorkspaceSelection(this, view, documents);
  }

  /**
   * Returns the folder.
   *
   * @return the folder's real path, or its absolute normalized path when it did not resolve; null
   *     when none is set
   */
  public Path root() {
    return root;
  }

  /**
   * Sets the directory names the browser and the index skip; with a folder set, it is listed and
   * indexed again.
   *
   * @param names {@code ;}-separated names
   */
  public void setExcludedDirectories(String names) {
    WalkRules next = WalkRules.of(names);
    if (!next.equals(rules)) {
      rules = next;
      if (shownFolder() != null && !disposed) {
        reset();
      }
    }
  }

  /** Asks for a folder and makes it the workspace. */
  public void choose() {
    view.chooseFolder(
        root,
        new Consumer<Path>() {
          @Override
          public void accept(Path folder) {
            set(folder);
          }
        });
  }

  /**
   * Resolves a folder's real path on a {@code qcaret-io-} worker, then makes it the workspace, says
   * so in the status bar, lists it again and rebuilds the index.
   *
   * @param folder the folder
   */
  public void set(Path folder) {
    final Path given = folder.toAbsolutePath().normalize();
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final Path real = realFolder(given);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (!disposed) {
                      root = real;
                      browsed = null;
                      view.showNotice(UiText.WORKSPACE_SET.format(real));
                      reset();
                      documents.sessionChanged();
                    }
                  }
                });
          }
        });
  }

  /**
   * Returns a search root's real path, which compares equal to open documents' paths and to the
   * files walked under it.
   *
   * @param folder an absolute normalized folder
   * @return its real path, or {@code folder} when it does not resolve; the search then reports it
   *     unreadable
   */
  @WorkerThread
  static Path realFolder(Path folder) {
    try {
      return folder.toRealPath();
    } catch (IOException e) {
      return folder;
    }
  }

  /** Drops every listing and the index, lists the root again when shown and builds the index. */
  private void reset() {
    if (index != null) {
      budget.release(index.charged());
      index = null;
    }
    relist();
    buildIndex();
  }

  /** Drops every listing and lists the shown folder again when the browser shows. */
  private void relist() {
    for (Listing listing : listings.values()) {
      cancel(listing);
    }
    listings.clear();
    revealing = null;
    view.workspaceChanged(null);
    if (shown && shownFolder() != null) {
      expand(rootNode());
    }
  }

  /** Sets what runs the row operations. */
  void setFiles(WorkspaceFiles value) {
    files = value;
  }

  @Override
  public WorkspaceFiles files() {
    return files;
  }

  /** Sets what runs the navigation, filter and sort. */
  void setBrowse(WorkspaceBrowse value) {
    browse = value;
  }

  @Override
  public WorkspaceBrowse browse() {
    return browse;
  }

  @Override
  public WorkspaceSelection selection() {
    return selection;
  }

  /** Joins the windows whose browsers follow each other's changes. */
  void setPeers(BrowserWindows value) {
    peers = value;
    value.add(this);
  }

  void setBeforeList(Runnable hook) {
    beforeList = hook;
  }

  /** Returns the folder the browser shows: the folder browsed to, else the workspace folder. */
  Path shownFolder() {
    return browsed != null ? browsed : root;
  }

  /** Returns the folder browsed to; null while the browser shows the workspace folder. */
  Path browsed() {
    return browsed;
  }

  /**
   * Shows a folder in the browser without changing the workspace folder.
   *
   * @param real the folder's real path; the workspace folder, or null, shows the workspace
   */
  void showFolder(Path real) {
    if (disposed) {
      return;
    }
    browsed = real == null || real.equals(root) ? null : real;
    relist();
    documents.sessionChanged();
  }

  /** Lists every listed directory again, after the filter, hidden entries or sort changed. */
  void relistAll() {
    if (disposed) {
      return;
    }
    for (Map.Entry<Path, Listing> entry : listings.entrySet()) {
      list(entry.getKey(), entry.getValue());
    }
    view.workspaceChanged(null);
    documents.sessionChanged();
  }

  /** Returns the entries the browser and the index leave out. */
  WalkRules rules() {
    return rules;
  }

  /** Stops the index build and the listings and returns the index's charge. */
  void dispose() {
    disposed = true;
    if (peers != null) {
      peers.remove(this);
    }
    if (files != null) {
      files.dispose();
    }
    for (Listing listing : listings.values()) {
      cancel(listing);
    }
    listings.clear();
    if (indexing != null) {
      indexing.cancel();
      indexing = null;
    }
    if (index != null) {
      budget.release(index.charged());
      index = null;
    }
    indexListeners.clear();
  }

  private static void cancel(Listing listing) {
    if (listing.reading != null) {
      listing.reading.cancel();
      listing.reading = null;
    }
  }

  // The browser.

  /** Reports whether the browser shows. */
  boolean shown() {
    return shown;
  }

  /** Shows the browser, or hides it. */
  void toggle() {
    setShown(!shown);
  }

  private void setShown(boolean value) {
    if (disposed || shown == value) {
      return;
    }
    shown = value;
    view.showWorkspace(this, shown);
    if (shown && shownFolder() != null) {
      expand(rootNode());
    }
    view.documentStateChanged();
    documents.sessionChanged();
  }

  @Override
  public String title() {
    return root == null ? UiText.WORKSPACE_NONE.text() : root.toString();
  }

  @Override
  public WorkspaceNode rootNode() {
    Path top = shownFolder();
    if (top == null) {
      return null;
    }
    Path name = top.getFileName();
    return new WorkspaceNode(
        top, name == null ? top.toString() : name.toString(), WorkspaceNode.Kind.DIRECTORY);
  }

  @Override
  public List<WorkspaceNode> children(WorkspaceNode directory) {
    Path dir = directory.path();
    Listing listing = listings.get(dir);
    if (listing == null) {
      return List.of();
    }
    if (listing.error != null) {
      return List.of(new WorkspaceNode(dir, listing.error, WorkspaceNode.Kind.ERROR));
    }
    if (listing.entries == null) {
      return List.of(
          new WorkspaceNode(dir, UiText.WORKSPACE_LOADING.text(), WorkspaceNode.Kind.LOADING));
    }
    List<WorkspaceNode> entries = listing.rows;
    if (entries.isEmpty()) {
      return List.of(
          new WorkspaceNode(dir, UiText.WORKSPACE_EMPTY.text(), WorkspaceNode.Kind.EMPTY));
    }
    int count = Math.min(listing.shown, entries.size());
    List<WorkspaceNode> nodes = new ArrayList<WorkspaceNode>(entries.subList(0, count));
    if (count < entries.size()) {
      int more = Math.min(PAGE, entries.size() - count);
      nodes.add(
          new WorkspaceNode(
              dir,
              UiText.WORKSPACE_MORE.format(more, entries.size() - count),
              WorkspaceNode.Kind.MORE));
    } else if (listing.entries.truncated()) {
      nodes.add(
          new WorkspaceNode(
              dir,
              UiText.WORKSPACE_TRUNCATED.format(DirectoryListing.MAX_ENTRIES),
              WorkspaceNode.Kind.ERROR));
    }
    return nodes;
  }

  @Override
  public void expand(WorkspaceNode directory) {
    if (disposed || !directory.expands() || listings.containsKey(directory.path())) {
      return;
    }
    Listing listing = new Listing();
    listings.put(directory.path(), listing);
    list(directory.path(), listing);
  }

  /** Lists a directory on a worker; its rows change when the listing arrives. */
  private void list(final Path dir, final Listing listing) {
    cancel(listing);
    final CancellationToken token = new CancellationToken();
    final WalkRules walk = rules;
    final ListingOptions how = browse == null ? ListingOptions.DEFAULTS : browse.options();
    listing.reading = token;
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            DirectoryListing read = null;
            List<WorkspaceNode> rows = null;
            String error = null;
            try {
              Runnable hook = beforeList;
              if (hook != null) {
                hook.run();
              }
              read = DirectoryListing.read(dir, walk, how, token);
              rows = BrowserRows.rows(dir, read, Locale.getDefault(), ZoneId.systemDefault());
            } catch (IOException e) {
              error = UiText.WORKSPACE_UNREADABLE.format(LogText.oneLine(String.valueOf(e)));
            } catch (CancellationException e) {
              error = null;
            } finally {
              final DirectoryListing result = rows == null ? null : read;
              final List<WorkspaceNode> shownRows = rows;
              final String failure = error;
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      listed(dir, listing, token, result, shownRows, failure);
                    }
                  });
            }
          }
        });
  }

  private void listed(
      Path dir,
      Listing listing,
      CancellationToken token,
      DirectoryListing read,
      List<WorkspaceNode> rows,
      String error) {
    if (disposed || listing.reading != token || listings.get(dir) != listing) {
      return;
    }
    listing.reading = null;
    if (read == null && error == null) {
      return;
    }
    listing.entries = read;
    listing.rows = rows;
    listing.error = error;
    view.workspaceChanged(directoryNode(dir));
    if (revealing != null) {
      reveal();
    }
    expandRestored();
  }

  // Session state.

  /** Directories a session expanded that wait for their parent's listing. */
  private final List<Path> restoredExpanded = new ArrayList<Path>();

  /**
   * Puts back a session's folders and browser ({@link WorkspaceBrowse#restoreFolders}); each
   * expanded directory expands once its parent is listed.
   *
   * @param real the workspace folder's real path, or null to keep none
   * @param other the real path of the folder the browser showed instead, or null
   * @param browser true to show the browser
   * @param expanded the expanded directories, parents first
   */
  void restored(Path real, Path other, boolean browser, List<Path> expanded) {
    if (disposed) {
      return;
    }
    if (real != null || other != null) {
      if (real != null) {
        root = real;
      }
      browsed = other == null || other.equals(root) ? null : other;
      restoredExpanded.addAll(expanded);
      reset();
    }
    setShown(browser);
  }

  /** Expands each restored directory whose parent is listed; the others wait. */
  private void expandRestored() {
    for (Path directory : new ArrayList<Path>(restoredExpanded)) {
      Listing parent = listings.get(directory.getParent());
      if (parent != null && parent.reading == null) {
        restoredExpanded.remove(directory);
        int at =
            parent.entries == null
                ? -1
                : indexOf(parent.entries.entries(), String.valueOf(directory.getFileName()));
        if (at >= 0 && at < parent.shown) {
          view.expandWorkspace(parent.rows.get(at));
        }
      }
    }
  }

  /** Returns the browser's expanded directories, parents first; empty while it is hidden. */
  List<String> expandedDirectories() {
    List<String> paths = new ArrayList<String>();
    if (shown) {
      for (WorkspaceNode node : view.expandedWorkspace()) {
        paths.add(node.path().toString());
      }
    }
    return paths;
  }

  private WorkspaceNode directoryNode(Path dir) {
    if (dir.equals(shownFolder())) {
      return rootNode();
    }
    return new WorkspaceNode(dir, String.valueOf(dir.getFileName()), WorkspaceNode.Kind.DIRECTORY);
  }

  @Override
  public void activate(WorkspaceNode node) {
    if (disposed) {
      return;
    }
    switch (node.kind()) {
      case FILE -> documents.open(List.of(node.path()));
      case MORE -> {
        Listing listing = listings.get(node.path());
        if (listing != null) {
          listing.shown += PAGE;
          view.workspaceChanged(directoryNode(node.path()));
        }
      }
      default -> {
        // Directories expand in the view; the other rows do nothing.
      }
    }
  }

  @Override
  public void refresh() {
    if (disposed || shownFolder() == null) {
      return;
    }
    List<Path> listed = new ArrayList<Path>(listings.keySet());
    relisted(listed);
    buildIndex();
    if (peers != null) {
      peers.refreshed(this, listed);
    }
  }

  /**
   * Lists directories again where this browser lists them.
   *
   * @param directories the directories
   */
  void relisted(List<Path> directories) {
    if (disposed) {
      return;
    }
    for (Path dir : directories) {
      Listing listing = listings.get(dir);
      if (listing != null) {
        list(dir, listing);
      }
    }
  }

  @Override
  public void setWorkspace(WorkspaceNode directory) {
    if (directory.expands()) {
      set(directory.path());
    }
  }

  @Override
  public void copyPath(WorkspaceNode node) {
    if (node.kind() == WorkspaceNode.Kind.FILE || node.expands()) {
      view.setText(node.path().toString());
    }
  }

  @Override
  public void openInDefaultApplication(WorkspaceNode node) {
    if (node.kind() != WorkspaceNode.Kind.FILE) {
      return;
    }
    final Path path = node.path();
    final boolean windows = view.launchesByExtension();
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final boolean runs = runsInsteadOfOpening(path, windows);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    launch(path, runs);
                  }
                });
          }
        });
  }

  private void launch(Path path, boolean runs) {
    if (disposed) {
      return;
    }
    String name = String.valueOf(path.getFileName());
    if (runs) {
      view.showNotice(UiText.WORKSPACE_RUNS.format(name));
    } else if (!view.launch(path)) {
      view.showNotice(UiText.WORKSPACE_LAUNCH_FAILED.format(name));
    }
  }

  /**
   * Reports whether the system would run a file rather than open it (QLogg SWT-8: on Windows {@code
   * Files.isExecutable} is true for every readable file).
   *
   * @param path the file
   * @param windows true on Windows, which decides by extension
   * @return true for a {@link #WINDOWS_EXECUTABLE} extension on Windows, an executable file
   *     elsewhere
   */
  @WorkerThread
  static boolean runsInsteadOfOpening(Path path, boolean windows) {
    if (windows) {
      Path name = path.getFileName();
      String text = name == null ? "" : name.toString().toLowerCase(Locale.ROOT);
      for (String extension : WINDOWS_EXECUTABLE) {
        if (text.endsWith(extension)) {
          return true;
        }
      }
      return false;
    }
    return Files.isExecutable(path);
  }

  @Override
  public void revealActive() {
    Path path = documents.activePath();
    if (disposed || path == null) {
      return;
    }
    Path top = shownFolder();
    if (top == null || !path.startsWith(top) || path.equals(top)) {
      UiText outside = browsed == null ? UiText.WORKSPACE_OUTSIDE : UiText.BROWSE_OUTSIDE;
      view.showNotice(outside.format(path.getFileName()));
      return;
    }
    revealing = path;
    reveal();
  }

  /**
   * Walks from the root to the file being revealed, listing each directory not yet listed and
   * showing enough entries to hold the next step; once every directory is listed, the view expands
   * them and selects the file's row.
   */
  private void reveal() {
    Path target = revealing;
    Path dir = shownFolder();
    if (target == null || dir == null) {
      return;
    }
    List<WorkspaceNode> chain = new ArrayList<WorkspaceNode>();
    for (Path element : dir.relativize(target)) {
      Listing listing = listings.get(dir);
      if (listing == null) {
        expand(directoryNode(dir));
        return;
      }
      if (listing.entries == null) {
        if (listing.error != null) {
          revealing = null;
        }
        return;
      }
      int at = indexOf(listing.entries.entries(), element.toString());
      if (at < 0) {
        revealing = null;
        view.showNotice(UiText.WORKSPACE_NOT_LISTED.format(target.getFileName()));
        return;
      }
      if (at >= listing.shown) {
        listing.shown = (at / PAGE + 1) * PAGE;
        view.workspaceChanged(directoryNode(dir));
      }
      WorkspaceNode next = listing.rows.get(at);
      chain.add(next);
      dir = next.path();
    }
    revealing = null;
    view.revealWorkspace(chain);
  }

  private static int indexOf(List<DirectoryListing.Entry> entries, String name) {
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public void hide() {
    setShown(false);
  }

  /**
   * Brings the browser and the index in line after a row operation, here and in every other window
   * ({@link #apply}).
   *
   * @param directory the directory that changed
   * @param removed the entry that went, or null
   * @param added the file the index gains, or null
   * @param folder true when a folder or a link changed, which builds the index again
   * @param select the row to select, or null
   */
  void changed(Path directory, Path removed, Path added, boolean folder, Path select) {
    if (disposed) {
      return;
    }
    apply(directory, removed, added, folder, select);
    if (peers != null) {
      peers.changed(this, directory, removed, added, folder);
    }
  }

  /**
   * Brings this browser and index in line after a row operation: lists the directory again where
   * listed, drops the listings under a removed folder, selects a row once listed, and, for a change
   * under the workspace folder, changes the index in place for one file or builds it again.
   *
   * @param directory the directory that changed
   * @param removed the entry that went, or null
   * @param added the file the index gains, or null
   * @param folder true when a folder or a link changed, which builds the index again
   * @param select the row to select, or null
   */
  void apply(Path directory, Path removed, Path added, boolean folder, Path select) {
    Path top = shownFolder();
    if (disposed || top == null) {
      return;
    }
    if (removed != null) {
      for (Path listed : new ArrayList<Path>(listings.keySet())) {
        if (listed.startsWith(removed)) {
          cancel(listings.remove(listed));
        }
      }
      if (shown && top.startsWith(removed)) {
        expand(rootNode());
      }
    }
    Listing listing = listings.get(directory);
    if (listing != null) {
      list(directory, listing);
    }
    if (select != null && select.startsWith(top) && !select.equals(top)) {
      revealing = select;
      if (listing == null) {
        reveal();
      }
    }
    if (root == null
        || !(directory.startsWith(root) || (removed != null && root.startsWith(removed)))) {
      return;
    }
    FileIndex next =
        folder || index == null || indexing != null
            ? null
            : index.withChange(removed, added, budget);
    if (next == null) {
      buildIndex();
    } else {
      budget.release(index.charged());
      index = next;
      for (Runnable listener : new ArrayList<Runnable>(indexListeners)) {
        listener.run();
      }
    }
  }

  /** Returns what moves or clears the folders after a rename or a delete. */
  WorkspaceFollow following() {
    return following;
  }

  /** Returns the windows whose browsers follow each other's changes, or null. */
  BrowserWindows peers() {
    return peers;
  }

  /** Reports whether the controller is disposed. */
  boolean disposed() {
    return disposed;
  }

  /**
   * Sets the workspace folder and the folder browsed to and lists again; a new workspace folder
   * builds the index again. Each expanded directory expands once its parent is listed. Writes the
   * session.
   *
   * @param nextRoot the workspace folder, or null
   * @param nextBrowsed the folder browsed to, or null
   * @param expanded the directories to expand
   */
  void relocate(Path nextRoot, Path nextBrowsed, List<Path> expanded) {
    boolean rootMoved = nextRoot != root;
    restoredExpanded.addAll(expanded);
    root = nextRoot;
    browsed = nextBrowsed;
    if (rootMoved) {
      reset();
    } else {
      relist();
    }
    documents.sessionChanged();
  }

  // The file index.

  /** Returns the newest finished index; null before the first build ends or without a folder. */
  FileIndex index() {
    return index;
  }

  /** Reports whether an index build runs. */
  boolean indexing() {
    return indexing != null;
  }

  /** Adds what runs after a build replaced the index. */
  void addIndexListener(Runnable listener) {
    indexListeners.add(listener);
  }

  /** Removes a listener {@link #addIndexListener} added. */
  void removeIndexListener(Runnable listener) {
    indexListeners.remove(listener);
  }

  private void buildIndex() {
    if (indexing != null) {
      indexing.cancel();
    }
    if (root == null || disposed) {
      indexing = null;
      return;
    }
    final CancellationToken token = new CancellationToken();
    indexing = token;
    final Path folder = root;
    final WalkRules walk = rules;
    boolean started =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                FileIndex built = null;
                try {
                  built = FileIndex.build(folder, walk, FileIndex.MAX_FILES, budget, token);
                } catch (CancellationException e) {
                  built = null;
                } finally {
                  final FileIndex result = built;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          indexed(token, result);
                        }
                      });
                }
              }
            });
    if (!started) {
      indexing = null;
    }
  }

  /** Keeps a finished index when it is the newest build's; returns its charge otherwise. */
  private void indexed(CancellationToken token, FileIndex built) {
    if (disposed || token != indexing) {
      if (built != null) {
        budget.release(built.charged());
      }
      return;
    }
    indexing = null;
    if (built == null) {
      return;
    }
    if (index != null) {
      budget.release(index.charged());
    }
    index = built;
    for (Runnable listener : new ArrayList<Runnable>(indexListeners)) {
      listener.run();
    }
  }
}
