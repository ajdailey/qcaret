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
 * As onto the entry and reload from writing or reading the source path. The workspace folder
 * follows a rename ({@link WorkspaceController#moved}). Delete of one entry or several runs in
 * {@link SelectionDelete}. Rename and Delete refuse while {@link OpenFile#running} holds a document
 * under the entry.
 */
final class WorkspaceFiles implements WorkspaceFilesInput {
  private final WorkspaceController workspace;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final DocumentController documents;
  private final RecentFiles recent;
  private final SessionController session;
  private final SearchController search;
  private final CancellationToken token = new CancellationToken();
  private final SelectionDelete many;

  /** Runs on the worker just before a rename moves the entry; a test seam, null otherwise. */
  private volatile Runnable beforeRename;

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
    this.many = new SelectionDelete(this, workspace, view, workers, ui, token);
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
  List<DocumentController> windows() {
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

  boolean below(WorkspaceNode node) {
    Path root = workspace.listings().shownFolder();
    return root != null && node.path().startsWith(root) && !node.path().equals(root);
  }

  static boolean entry(WorkspaceNode node) {
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
    final List<DocumentController> held = windows();
    for (DocumentController window : held) {
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
                    renamed(listed, held, move, folder, indexedName, failure);
                  }
                });
          }
        });
  }

  /**
   * Applies a rename's result. The hold ends in every window it was taken in and every window
   * holding a document now, so a window a held tab moved out of keeps no hold.
   */
  private void renamed(
      BrowserPath listed,
      List<DocumentController> held,
      PathMove move,
      boolean folder,
      boolean indexed,
      String error) {
    List<DocumentController> holders = windows();
    for (DocumentController window : held) {
      if (!holders.contains(window)) {
        holders.add(window);
      }
    }
    for (DocumentController window : holders) {
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

  /** Shows a notice and returns true while {@link OpenFile#running} holds a document under it. */
  boolean busy(BrowserPath entry) {
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
    deleteAll(List.of(node));
  }

  /**
   * Asks once, then deletes entries permanently; see {@link SelectionDelete}.
   *
   * @param nodes file or directory rows below the shown folder
   */
  void deleteAll(List<WorkspaceNode> nodes) {
    if (!token.isCanceled()) {
      many.delete(nodes);
    }
  }
}
