package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.workspace.DirectoryListing;
import io.github.ajdailey.qcaret.core.workspace.ListingOptions;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * The workspace browser's shown folder and its directory listings for one {@link
 * WorkspaceController}. The shown folder is the folder browsed to, else the workspace folder. A
 * directory is listed on a {@code qcaret-io-} worker the first time it expands ({@link
 * DirectoryListing} with the workspace's {@link WalkRules} and {@link WorkspaceBrowse#options}),
 * with the rows' Size and Modified texts ({@link BrowserRows}), and shows {@value #PAGE} entries at
 * a time behind a "more" row. Listing again keeps the old rows until the new ones arrive; dropping
 * a listing cancels its read, and a result for a dropped or replaced listing is dropped. Confined
 * to the UI thread.
 */
final class BrowserListings {
  /** Entries a directory shows at once; the "more" row adds as many again. */
  static final int PAGE = 500;

  private final WorkspaceController workspace;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final Map<Path, Listing> listings = new HashMap<Path, Listing>();

  /** Directories a session expanded that wait for their parent's listing. */
  private final List<Path> restoredExpanded = new ArrayList<Path>();

  private boolean shown;
  private boolean disposed;
  private Path revealing;

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

  BrowserListings(WorkspaceController workspace, MainView view, Workers workers, UiDispatcher ui) {
    this.workspace = workspace;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
  }

  void setBeforeList(Runnable hook) {
    beforeList = hook;
  }

  /** Returns the folder the browser shows: the folder browsed to, else the workspace folder. */
  Path shownFolder() {
    return browsed != null ? browsed : workspace.root();
  }

  /** Returns the folder browsed to; null while the browser shows the workspace folder. */
  Path browsed() {
    return browsed;
  }

  /** Sets the folder browsed to as given; null shows the workspace folder. */
  void setBrowsed(Path folder) {
    browsed = folder;
  }

  /** Sets the folder browsed to; the workspace folder, or null, shows the workspace folder. */
  void browse(Path folder) {
    browsed = folder == null || folder.equals(workspace.root()) ? null : folder;
  }

  /** Reports whether the browser shows. */
  boolean shown() {
    return shown;
  }

  /** Records whether the browser shows. */
  void setShown(boolean value) {
    shown = value;
  }

  /** Lists the shown folder when the browser shows and it is not listed yet. */
  void listRoot() {
    if (shown && shownFolder() != null) {
      expand(rootNode());
    }
  }

  /** Drops every listing and lists the shown folder again when the browser shows. */
  void relist() {
    for (Listing listing : listings.values()) {
      cancel(listing);
    }
    listings.clear();
    revealing = null;
    view.workspaceChanged(null);
    listRoot();
  }

  /** Lists every listed directory again, after the filter, hidden entries or sort changed. */
  void relistAll() {
    for (Map.Entry<Path, Listing> entry : listings.entrySet()) {
      list(entry.getKey(), entry.getValue());
    }
    view.workspaceChanged(null);
  }

  /** Returns the listed directories. */
  List<Path> listedDirectories() {
    return new ArrayList<Path>(listings.keySet());
  }

  /**
   * Lists directories again where this browser lists them.
   *
   * @param directories the directories
   */
  void relisted(List<Path> directories) {
    for (Path dir : directories) {
      Listing listing = listings.get(dir);
      if (listing != null) {
        list(dir, listing);
      }
    }
  }

  /** Cancels and drops every listing. */
  void dispose() {
    disposed = true;
    for (Listing listing : listings.values()) {
      cancel(listing);
    }
    listings.clear();
  }

  private static void cancel(Listing listing) {
    if (listing.reading != null) {
      listing.reading.cancel();
      listing.reading = null;
    }
  }

  /** Returns the shown folder's row; null without one. */
  WorkspaceNode rootNode() {
    Path top = shownFolder();
    if (top == null) {
      return null;
    }
    Path name = top.getFileName();
    return new WorkspaceNode(
        top, name == null ? top.toString() : name.toString(), WorkspaceNode.Kind.DIRECTORY);
  }

  /** Returns a directory's rows: its listing's page, a loading, empty or error row, or none. */
  List<WorkspaceNode> children(WorkspaceNode directory) {
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

  /** Lists a directory not yet listed. */
  void expand(WorkspaceNode directory) {
    if (disposed || !directory.expands() || listings.containsKey(directory.path())) {
      return;
    }
    Listing listing = new Listing();
    listings.put(directory.path(), listing);
    list(directory.path(), listing);
  }

  /** Shows {@value #PAGE} more rows of a "more" row's directory. */
  void more(WorkspaceNode node) {
    Listing listing = listings.get(node.path());
    if (listing != null) {
      listing.shown += PAGE;
      view.workspaceChanged(directoryNode(node.path()));
    }
  }

  /** Lists a directory on a worker; its rows change when the listing arrives. */
  private void list(final Path dir, final Listing listing) {
    cancel(listing);
    final CancellationToken token = new CancellationToken();
    final WalkRules walk = workspace.rules();
    WorkspaceBrowse browse = workspace.browse();
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

  /**
   * Expands each directory once its parent is listed.
   *
   * @param expanded the directories, parents first
   */
  void restoreExpanded(List<Path> expanded) {
    restoredExpanded.addAll(expanded);
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

  /**
   * Selects a file's row, listing each directory down to it, or says why not.
   *
   * @param path the file
   */
  void revealFile(Path path) {
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

  /**
   * Lists a directory again after a row operation where listed, drops the listings under a removed
   * folder, and selects a row once listed.
   *
   * @param directory the directory that changed
   * @param removed the entry that went, or null
   * @param select the row to select, or null
   */
  void changed(Path directory, Path removed, Path select) {
    Path top = shownFolder();
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
  }
}
