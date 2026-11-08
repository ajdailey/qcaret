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
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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
 * <p>The browser lists a directory on a {@code qcaret-io-} worker the first time it expands ({@link
 * DirectoryListing}, {@code workspace.excludedDirectories}), and shows {@value #PAGE} entries at a
 * time behind a "more" row. Refresh lists every listed directory again, keeping the old rows until
 * the new ones arrive, and rebuilds the index. The row menu's file operations run in {@link
 * WorkspaceFiles}.
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

  /** One directory's listing as the browser holds it. */
  private static final class Listing {
    DirectoryListing entries;
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
      if (root != null && !disposed) {
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
    for (Listing listing : listings.values()) {
      cancel(listing);
    }
    listings.clear();
    revealing = null;
    if (index != null) {
      budget.release(index.charged());
      index = null;
    }
    view.workspaceChanged(null);
    if (shown && root != null) {
      expand(rootNode());
    }
    buildIndex();
  }

  /** Sets what runs the row operations. */
  void setFiles(WorkspaceFiles value) {
    files = value;
  }

  @Override
  public WorkspaceFiles files() {
    return files;
  }

  /** Returns the entries the browser and the index leave out. */
  WalkRules rules() {
    return rules;
  }

  /** Stops the index build and the listings and returns the index's charge. */
  void dispose() {
    disposed = true;
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
    if (shown && root != null) {
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
    if (root == null) {
      return null;
    }
    Path name = root.getFileName();
    return new WorkspaceNode(
        root, name == null ? root.toString() : name.toString(), WorkspaceNode.Kind.DIRECTORY);
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
    List<DirectoryListing.Entry> entries = listing.entries.entries();
    if (entries.isEmpty()) {
      return List.of(
          new WorkspaceNode(dir, UiText.WORKSPACE_EMPTY.text(), WorkspaceNode.Kind.EMPTY));
    }
    int count = Math.min(listing.shown, entries.size());
    List<WorkspaceNode> nodes = new ArrayList<WorkspaceNode>(count + 1);
    for (int i = 0; i < count; i++) {
      nodes.add(node(dir, entries.get(i)));
    }
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

  private static WorkspaceNode node(Path dir, DirectoryListing.Entry entry) {
    WorkspaceNode.Kind kind =
        switch (entry.kind()) {
          case DIRECTORY -> WorkspaceNode.Kind.DIRECTORY;
          case FILE -> WorkspaceNode.Kind.FILE;
          case CYCLE -> WorkspaceNode.Kind.CYCLE;
        };
    return new WorkspaceNode(dir.resolve(entry.name()), entry.name(), kind);
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
    listing.reading = token;
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            DirectoryListing read = null;
            String error = null;
            try {
              read = DirectoryListing.read(dir, walk, token);
            } catch (IOException e) {
              error = UiText.WORKSPACE_UNREADABLE.format(LogText.oneLine(String.valueOf(e)));
            } catch (CancellationException e) {
              error = null;
            } finally {
              final DirectoryListing result = read;
              final String failure = error;
              ui.asyncExec(
                  new Runnable() {
                    @Override
                    public void run() {
                      listed(dir, listing, token, result, failure);
                    }
                  });
            }
          }
        });
  }

  private void listed(
      Path dir, Listing listing, CancellationToken token, DirectoryListing read, String error) {
    if (disposed || listing.reading != token || listings.get(dir) != listing) {
      return;
    }
    listing.reading = null;
    if (read == null && error == null) {
      return;
    }
    listing.entries = read;
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
   * Puts back a session's folder and browser: the folder's real path is resolved on a {@code
   * qcaret-io-} worker, a folder that is gone is dropped, and each directory expands once its
   * parent is listed.
   *
   * @param folder the folder, empty for none
   * @param browser true to show the browser
   * @param expanded the expanded directories, parents first
   */
  void restore(String folder, final boolean browser, final List<Path> expanded) {
    if (folder.isEmpty()) {
      setShown(browser);
      return;
    }
    final Path given;
    try {
      given = Path.of(folder).toAbsolutePath().normalize();
    } catch (InvalidPathException e) {
      setShown(browser);
      return;
    }
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final Path real = Files.isDirectory(given) ? realFolder(given) : null;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (disposed) {
                      return;
                    }
                    if (real != null) {
                      root = real;
                      restoredExpanded.addAll(expanded);
                      reset();
                    }
                    setShown(browser);
                  }
                });
          }
        });
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
          view.expandWorkspace(node(directory.getParent(), parent.entries.entries().get(at)));
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
    if (dir.equals(root)) {
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
    if (disposed || root == null) {
      return;
    }
    for (Map.Entry<Path, Listing> entry : listings.entrySet()) {
      list(entry.getKey(), entry.getValue());
    }
    buildIndex();
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
    if (root == null || !path.startsWith(root) || path.equals(root)) {
      view.showNotice(UiText.WORKSPACE_OUTSIDE.format(path.getFileName()));
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
    Path dir = root;
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
      WorkspaceNode next = node(dir, listing.entries.entries().get(at));
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
   * Brings the browser and the index in line after a row operation: lists the directory again,
   * drops the listings under a removed folder, selects a row once listed, and changes the index in
   * place for one file or builds it again.
   *
   * @param directory the directory that changed
   * @param removed the entry that went, or null
   * @param added the file the index gains, or null
   * @param folder true when a folder or a link changed, which builds the index again
   * @param select the row to select, or null
   */
  void changed(Path directory, Path removed, Path added, boolean folder, Path select) {
    if (disposed || root == null) {
      return;
    }
    if (removed != null) {
      for (Path listed : new ArrayList<Path>(listings.keySet())) {
        if (listed.startsWith(removed)) {
          cancel(listings.remove(listed));
        }
      }
    }
    Listing listing = listings.get(directory);
    if (listing != null) {
      list(directory, listing);
    }
    if (select != null && select.startsWith(root)) {
      revealing = select;
      if (listing == null) {
        reveal();
      }
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
