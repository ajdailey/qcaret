package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WorkspaceInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The window's workspace folder, set with File > Set Workspace Folder and kept for the run, with
 * its browser and file index. HyperSearch's workspace scope searches its tree. Confined to the UI
 * thread.
 *
 * <p>The browser shows the workspace folder, or a folder browsed to through {@link WorkspaceBrowse}
 * without changing the workspace; {@link BrowserListings} holds the shown folder and its listings.
 * Refresh, and a filter, hidden or sort change, list every listed directory again; Refresh also
 * rebuilds the index. A new shown folder drops every listing. The row menu over the selected rows
 * runs through {@link WorkspaceSelection}, its file operations in {@link WorkspaceFiles}; after
 * one, or a Refresh, every other window's browser lists the directories it shows again ({@link
 * BrowserWindows}). A rename of the workspace folder or a parent moves the workspace in every
 * window ({@link WorkspaceFollow#follow}); a delete of either clears it ({@link
 * WorkspaceFollow#clearUnder}).
 *
 * <p>The file index ({@link FileIndex}, at most {@link FileIndex#MAX_FILES} files, charged to the
 * {@link MemoryBudget}, held in {@link FileIndexHolder}) is built on a {@code qcaret-search-}
 * worker whenever the folder changes and on Refresh; a new build cancels the running one. A folder
 * change drops the index at once; Refresh keeps it until the new one arrives. A dropped, replaced
 * or stale index returns its charge, and {@link #dispose} cancels the build and returns the charge.
 */
public final class WorkspaceController implements WorkspaceInput {
  /** Extensions Windows runs rather than opens. */
  static final List<String> WINDOWS_EXECUTABLE =
      List.of(
          ".bat", ".cmd", ".com", ".cpl", ".exe", ".hta", ".js", ".jse", ".lnk", ".msc", ".msi",
          ".pif", ".ps1", ".reg", ".scr", ".url", ".vbe", ".vbs", ".wsf", ".wsh");

  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final DocumentController documents;

  /** The entries the browser and the index leave out ({@code workspace.excludedDirectories}). */
  private WalkRules rules = WalkRules.of(FileFilter.DEFAULT_EXCLUDED_DIRECTORIES);

  private Path root;
  private boolean disposed;
  private WorkspaceFiles files;
  private WorkspaceBrowse browse;
  private BrowserWindows peers;
  private final WorkspaceFollow following;
  private final WorkspaceSelection selection;
  private final BrowserListings listings;
  private final FileIndexHolder fileIndex;

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
    this.following = new WorkspaceFollow(this, view);
    this.selection = new WorkspaceSelection(this, view, documents);
    this.listings = new BrowserListings(this, view, workers, ui);
    this.fileIndex = new FileIndexHolder(this, workers, ui, budget);
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
      if (listings.shownFolder() != null && !disposed) {
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
    new BusyWork(ui, view) {
      @Override
      @WorkerThread
      void work() {
        final Path real = realFolder(given);
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                if (!disposed) {
                  root = real;
                  listings.setBrowsed(null);
                  view.showNotice(UiText.WORKSPACE_SET.format(real));
                  reset();
                  documents.sessionChanged();
                }
              }
            });
      }
    }.start(workers, Workers.Kind.IO);
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
    fileIndex.drop();
    listings.relist();
    fileIndex.build();
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

  /** Returns the shown folder and its listings. */
  BrowserListings listings() {
    return listings;
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
    listings.browse(real);
    listings.relist();
    documents.sessionChanged();
  }

  /** Lists every listed directory again, after the filter, hidden entries or sort changed. */
  void relistAll() {
    if (disposed) {
      return;
    }
    listings.relistAll();
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
    listings.dispose();
    fileIndex.dispose();
  }

  // The browser.

  /** Reports whether the browser shows. */
  boolean shown() {
    return listings.shown();
  }

  /** Shows the browser, or hides it. */
  void toggle() {
    setShown(!listings.shown());
  }

  private void setShown(boolean value) {
    if (disposed || listings.shown() == value) {
      return;
    }
    listings.setShown(value);
    view.showWorkspace(this, value);
    listings.listRoot();
    view.documentStateChanged();
    documents.sessionChanged();
  }

  @Override
  public String title() {
    return root == null ? UiText.WORKSPACE_NONE.text() : root.toString();
  }

  @Override
  public WorkspaceNode rootNode() {
    return listings.rootNode();
  }

  @Override
  public List<WorkspaceNode> children(WorkspaceNode directory) {
    return listings.children(directory);
  }

  @Override
  public void expand(WorkspaceNode directory) {
    listings.expand(directory);
  }

  // Session state.

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
      listings.browse(other);
      listings.restoreExpanded(expanded);
      reset();
    }
    setShown(browser);
  }

  @Override
  public void activate(WorkspaceNode node) {
    if (disposed) {
      return;
    }
    switch (node.kind()) {
      case FILE -> documents.open(List.of(node.path()));
      case MORE -> listings.more(node);
      default -> {
        // Directories expand in the view; the other rows do nothing.
      }
    }
  }

  @Override
  public void refresh() {
    if (disposed || listings.shownFolder() == null) {
      return;
    }
    List<Path> listed = listings.listedDirectories();
    relisted(listed);
    fileIndex.build();
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
    listings.relisted(directories);
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
    new BusyWork(ui, view) {
      @Override
      @WorkerThread
      void work() {
        final boolean runs = runsInsteadOfOpening(path, windows);
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                launch(path, runs);
              }
            });
      }
    }.start(workers, Workers.Kind.IO);
  }

  private void launch(Path path, boolean runs) {
    if (disposed) {
      return;
    }
    String name = String.valueOf(path.getFileName());
    if (runs) {
      view.showNotice(UiText.WORKSPACE_RUNS.format(name));
    } else if (!LaunchCursor.launch(view, ui, path)) {
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
    listings.revealFile(path);
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
    if (disposed || listings.shownFolder() == null) {
      return;
    }
    listings.changed(directory, removed, select);
    if (root == null
        || !(directory.startsWith(root) || (removed != null && root.startsWith(removed)))) {
      return;
    }
    fileIndex.changed(removed, added, folder);
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
    listings.restoreExpanded(expanded);
    root = nextRoot;
    listings.setBrowsed(nextBrowsed);
    if (rootMoved) {
      reset();
    } else {
      listings.relist();
    }
    documents.sessionChanged();
  }

  // The file index: FileIndexHolder.

  /** Returns the newest finished index; null before the first build ends or without a folder. */
  FileIndex index() {
    return fileIndex.index();
  }

  /** Reports whether an index build runs. */
  boolean indexing() {
    return fileIndex.indexing();
  }

  /** Adds what runs after a build replaced the index. */
  void addIndexListener(Runnable listener) {
    fileIndex.addIndexListener(listener);
  }

  /** Removes a listener {@link #addIndexListener} added. */
  void removeIndexListener(Runnable listener) {
    fileIndex.removeIndexListener(listener);
  }
}
