package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WorkspaceFilesInput;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.workspace.FileOperations;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * The workspace browser's row operations: Search in Folder, New File, New Folder, Rename and
 * Delete. Names are checked on the UI thread, files are touched on {@code qcaret-io-} workers, and
 * each operation that changes a directory lists it again, updates the file index and selects the
 * new row through {@link WorkspaceController#changed}. Confined to the UI thread.
 *
 * <p>Rename moves every open document under the entry, in every window, through {@link PathMove},
 * and the Recent Files entries and the remembered state of closed files with them. From the moment
 * the name is accepted until the move lands or fails, {@link DocumentsUnder#hold} keeps Save, Save
 * As onto the entry and reload from writing or reading the source path. Delete is permanent: the
 * prompt names the entry, counts a folder's contents first and lists the open documents under it
 * with unsaved changes; afterwards those documents are checked, which marks a deleted file missing.
 * The workspace folder follows a rename and is cleared by a finished delete ({@link
 * WorkspaceController#moved}, {@link WorkspaceController#deleted}). Rename and Delete refuse while
 * a save, a reload or a change prompt holds a document under the entry.
 */
final class WorkspaceFiles implements WorkspaceFilesInput {
  /** Unsaved document names the delete prompt lists before "and N more". */
  static final int LISTED_NAMES = 10;

  private final WorkspaceController workspace;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final DocumentController documents;
  private final RecentFiles recent;
  private final SessionController session;
  private final SearchController search;
  private final CancellationToken token = new CancellationToken();

  /** Runs on the worker just before a rename moves the entry; a test seam, null otherwise. */
  private volatile Runnable beforeRename;

  /** What a worker found about an entry before a rename or a delete. */
  record Found(BrowserPath entry, boolean folder, FileOperations.Counts counts) {}

  WorkspaceFiles(
      WorkspaceController workspace,
      MainView view,
      Workers workers,
      UiDispatcher ui,
      DocumentController documents,
      RecentFiles recent,
      SessionController session,
      SearchController search) {
    this.workspace = workspace;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
    this.documents = documents;
    this.recent = recent;
    this.session = session;
    this.search = search;
  }

  void setBeforeRename(Runnable hook) {
    beforeRename = hook;
  }

  /** Stops a running count or delete at its next entry; the operations do nothing afterwards. */
  void dispose() {
    token.cancel();
  }

  /**
   * Reports whether a name is a single file name: not blank, not {@code .} or {@code ..}, without
   * {@code /}, {@code \} or NUL.
   *
   * @param name the name typed
   * @return true when it is
   */
  static boolean plainName(String name) {
    return !name.isBlank()
        && !name.equals(".")
        && !name.equals("..")
        && name.indexOf('/') < 0
        && name.indexOf('\\') < 0
        && name.indexOf(File.separatorChar) < 0
        && name.indexOf('\0') < 0;
  }

  /** Returns this window and every other window holding a document. */
  private List<DocumentController> windows() {
    List<DocumentController> holders = new ArrayList<DocumentController>();
    holders.add(documents);
    for (Document document : documents.documentsAnywhere()) {
      DocumentController holder = documents.holderOf(document);
      if (holder != null && !holders.contains(holder)) {
        holders.add(holder);
      }
    }
    return holders;
  }

  private boolean below(WorkspaceNode node) {
    Path root = workspace.shownFolder();
    return root != null && node.path().startsWith(root) && !node.path().equals(root);
  }

  private static boolean entry(WorkspaceNode node) {
    return node.kind() == WorkspaceNode.Kind.FILE
        || node.kind() == WorkspaceNode.Kind.DIRECTORY
        || node.kind() == WorkspaceNode.Kind.CYCLE;
  }

  // Search in Folder.

  @Override
  public void searchInFolder(WorkspaceNode node) {
    if (token.isCanceled()) {
      return;
    }
    if (node.expands()) {
      search.showDialog(node.path());
    } else if (node.kind() == WorkspaceNode.Kind.FILE && node.path().getParent() != null) {
      search.showDialog(node.path().getParent());
    }
  }

  // New File and New Folder.

  @Override
  public void newFile(WorkspaceNode directory) {
    newEntry(directory, false);
  }

  @Override
  public void newFolder(WorkspaceNode directory) {
    newEntry(directory, true);
  }

  private void newEntry(WorkspaceNode directory, final boolean folder) {
    if (token.isCanceled() || !directory.expands()) {
      return;
    }
    final Path dir = directory.path();
    view.askFileName(
        (folder ? UiText.NEW_FOLDER_TITLE : UiText.NEW_FILE_TITLE).text(),
        UiText.NEW_ENTRY_MESSAGE.format(directory.name()),
        "",
        0,
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            if (name != null) {
              create(dir, name, folder);
            }
          }
        });
  }

  private void create(final Path dir, final String name, final boolean folder) {
    final String title = (folder ? UiText.NEW_FOLDER_TITLE : UiText.NEW_FILE_TITLE).text();
    if (!plainName(name)) {
      view.showInfo(title, UiText.FILE_NAME_INVALID.format(name));
      return;
    }
    final WalkRules rules = workspace.rules();
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            Path made = null;
            boolean indexed = false;
            String error;
            try {
              SafePaths.userFile(dir.resolve(name));
              made = FileOperations.create(dir, name, folder);
              indexed = !folder && indexed(made, rules);
              error = null;
            } catch (FileAlreadyExistsException e) {
              error = UiText.FILE_EXISTS.format(name);
            } catch (IOException | InvalidPathException e) {
              error = UiText.CREATE_FAILED.format(name, LogText.oneLine(e.getMessage()));
            }
            final Path result = made;
            final boolean listed = indexed;
            final String failure = error;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    created(dir, result, folder, listed, title, failure);
                  }
                });
          }
        });
  }

  private void created(
      Path dir, Path made, boolean folder, boolean indexed, String title, String error) {
    if (error != null) {
      view.showInfo(title, error);
      return;
    }
    workspace.changed(dir, null, indexed ? made : null, folder, made);
    if (!folder) {
      documents.open(List.of(made));
    }
  }

  /**
   * Reports whether the file index lists a file: not left out, and reached without a link, as the
   * index walk follows none.
   */
  @WorkerThread
  static boolean indexed(Path file, WalkRules rules) throws IOException {
    Path parent = file.getParent();
    return parent != null && !rules.skips(file, false) && parent.toRealPath().equals(parent);
  }

  // Rename.

  @Override
  public void rename(final WorkspaceNode node) {
    if (token.isCanceled() || !entry(node) || !below(node)) {
      return;
    }
    final String old = String.valueOf(node.path().getFileName());
    int dot = old.lastIndexOf('.');
    int stem = node.kind() == WorkspaceNode.Kind.FILE && dot > 0 ? dot : old.length();
    view.askFileName(
        UiText.RENAME_TITLE.text(),
        UiText.RENAME_MESSAGE.format(old),
        old,
        stem,
        new Consumer<String>() {
          @Override
          public void accept(String name) {
            if (name != null && !name.equals(old)) {
              renameTo(node, name);
            }
          }
        });
  }

  private void renameTo(WorkspaceNode node, final String name) {
    final Path source = node.path();
    final boolean folder = node.kind() != WorkspaceNode.Kind.FILE;
    if (!plainName(name)) {
      view.showInfo(UiText.RENAME_TITLE.text(), UiText.FILE_NAME_INVALID.format(name));
      return;
    }
    final BrowserPath listed = new BrowserPath(source, source);
    if (busy(listed)) {
      return;
    }
    for (DocumentController window : windows()) {
      window.under().hold(listed);
    }
    final WalkRules rules = workspace.rules();
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            BrowserPath from = null;
            BrowserPath to = null;
            boolean indexed = false;
            String error = null;
            try {
              Path real = realUnlessLink(source);
              Path target = source.resolveSibling(name);
              if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                SafePaths.userFile(target);
              }
              Runnable hook = beforeRename;
              if (hook != null) {
                hook.run();
              }
              Path renamed = FileOperations.rename(source, name);
              from = new BrowserPath(source, real);
              to = new BrowserPath(renamed, real == null ? null : real.resolveSibling(name));
              indexed = !folder && indexed(renamed, rules);
            } catch (FileAlreadyExistsException e) {
              error = UiText.FILE_EXISTS.format(name);
            } catch (IOException | InvalidPathException e) {
              error =
                  UiText.RENAME_FAILED.format(
                      source.getFileName(), LogText.oneLine(e.getMessage()));
            }
            final PathMove move = error == null ? new PathMove(from, to) : null;
            final boolean indexedName = indexed;
            final String failure = error;
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    renamed(listed, move, folder, indexedName, failure);
                  }
                });
          }
        });
  }

  private void renamed(
      BrowserPath listed, PathMove move, boolean folder, boolean indexed, String error) {
    for (DocumentController window : windows()) {
      if (move != null) {
        window.under().moved(move);
      }
      window.under().release(listed);
    }
    if (error != null) {
      view.showInfo(UiText.RENAME_TITLE.text(), error);
      return;
    }
    recent.moved(move);
    session.moved(move);
    workspace.following().moved(move);
    Path source = move.from().path();
    Path target = move.to().path();
    workspace.changed(source.getParent(), source, indexed ? target : null, folder, target);
  }

  /** Returns an entry's real path, or null for a link or a junction. */
  @WorkerThread
  static Path realUnlessLink(Path entry) throws IOException {
    if (Files.isSymbolicLink(entry)
        || (!FileOperations.plainDirectory(entry)
            && Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))) {
      return null;
    }
    return entry.toRealPath();
  }

  private boolean busy(BrowserPath entry) {
    for (DocumentController window : windows()) {
      if (window.under().busyUnder(entry)) {
        view.showNotice(UiText.FILE_BUSY.format(entry.path().getFileName()));
        return true;
      }
    }
    return false;
  }

  // Delete.

  @Override
  public void delete(WorkspaceNode node) {
    if (token.isCanceled() || !entry(node) || !below(node)) {
      return;
    }
    final Path path = node.path();
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                Found found = null;
                String error = null;
                try {
                  Path real = realUnlessLink(path);
                  boolean folder = real != null && FileOperations.plainDirectory(path);
                  found =
                      new Found(
                          new BrowserPath(path, real),
                          folder,
                          folder ? FileOperations.count(path, token) : null);
                } catch (IOException e) {
                  error = LogText.oneLine(e.getMessage());
                } catch (CancellationException e) {
                  error = null;
                }
                final Found result = found;
                final String failure = error;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        view.setIdle();
                        counted(path, result, failure);
                      }
                    });
              }
            });
    if (!started) {
      view.setIdle();
    }
  }

  private void counted(Path path, final Found found, String error) {
    if (token.isCanceled()) {
      return;
    }
    if (found == null) {
      if (error != null) {
        view.showInfo(
            UiText.DELETE_TITLE.text(), UiText.DELETE_UNREADABLE.format(path.getFileName(), error));
      }
      return;
    }
    if (busy(found.entry())) {
      return;
    }
    List<String> dirty = new ArrayList<String>();
    for (DocumentController window : windows()) {
      dirty.addAll(window.under().dirtyUnder(found.entry()));
    }
    view.askDelete(
        question(found, dirty),
        new Runnable() {
          @Override
          public void run() {
            deleteNow(found);
          }
        });
  }

  /** Returns the delete prompt: what goes, that it cannot be undone, the unsaved documents. */
  static String question(Found found, List<String> dirty) {
    String name = String.valueOf(found.entry().path().getFileName());
    String text;
    if (found.folder()) {
      text =
          UiText.DELETE_FOLDER_MESSAGE.format(
              name, found.counts().files(), found.counts().folders());
    } else if (found.entry().real() == null) {
      text = UiText.DELETE_LINK_MESSAGE.format(name);
    } else {
      text = UiText.DELETE_FILE_MESSAGE.format(name);
    }
    if (dirty.isEmpty()) {
      return text;
    }
    List<String> shown = dirty.subList(0, Math.min(LISTED_NAMES, dirty.size()));
    String names = String.join(", ", shown);
    if (dirty.size() > shown.size()) {
      names += ", +" + (dirty.size() - shown.size());
    }
    return text + "\n\n" + UiText.DELETE_UNSAVED.format(names);
  }

  private void deleteNow(final Found found) {
    final Path path = found.entry().path();
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final FileOperations.Deleted result = FileOperations.delete(path, token);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        view.setIdle();
                        deleted(found, result);
                      }
                    });
              }
            });
    if (!started) {
      view.setIdle();
    }
  }

  private void deleted(Found found, FileOperations.Deleted result) {
    for (DocumentController window : windows()) {
      window.under().deleted(found.entry());
    }
    if (result.done()) {
      workspace.following().deleted(found.entry());
    }
    Path path = found.entry().path();
    boolean folder = found.folder() || found.entry().real() == null;
    workspace.changed(path.getParent(), path, null, folder, null);
    if (!result.done() && !token.isCanceled()) {
      view.showInfo(
          UiText.DELETE_TITLE.text(),
          UiText.DELETE_FAILED.format(
              path.getFileName(),
              result.failed(),
              LogText.oneLine(result.error()),
              result.remaining().files(),
              result.remaining().folders()));
    }
  }
}
