package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.workspace.FileOperations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The workspace browser's permanent Delete of one entry or several, after one prompt. An entry
 * under another selected entry is left out, since deleting the outer one takes it. A {@code
 * qcaret-io-} worker reads each entry and counts each folder's contents; an entry that cannot be
 * read refuses the whole delete. The prompt names the entry, or the count and the first {@link
 * #LISTED_NAMES} names with the folders' contents, says that it cannot be undone and that links go
 * as links, and lists the unsaved open documents under any entry. A second worker deletes the
 * entries in order; a folder stops at its first entry that fails, the other entries still go, and
 * one message reports every stop. Afterwards the open documents under each entry are checked, which
 * marks a deleted file missing, a finished delete of the workspace folder or a parent clears it
 * ({@link WorkspaceController#deleted}), and each directory is listed again. Confined to the UI
 * thread.
 */
final class SelectionDelete {
  /** Names the delete prompt lists before {@code +N}. */
  static final int LISTED_NAMES = 10;

  private final WorkspaceFiles files;
  private final WorkspaceController workspace;
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private final CancellationToken token;

  /** What a worker found about an entry before a delete. */
  record Found(BrowserPath entry, boolean folder, FileOperations.Counts counts) {}

  SelectionDelete(
      WorkspaceFiles files,
      WorkspaceController workspace,
      MainView view,
      Workers workers,
      UiDispatcher ui,
      CancellationToken token) {
    this.files = files;
    this.workspace = workspace;
    this.view = view;
    this.workers = workers;
    this.ui = ui;
    this.token = token;
  }

  /**
   * Reads the entries, asks, then deletes them.
   *
   * @param nodes the selected rows; rows that are not entries below the shown folder are left out
   */
  void delete(List<WorkspaceNode> nodes) {
    final List<Path> paths = outermost(nodes);
    if (paths.isEmpty()) {
      return;
    }
    new BusyWork(ui, view) {
      private List<Found> found = new ArrayList<Found>();
      private Path unread;
      private String error;

      @Override
      @WorkerThread
      void work() {
        try {
          for (Path path : paths) {
            unread = path;
            found.add(find(path, token));
          }
          unread = null;
        } catch (IOException e) {
          error = LogText.oneLine(ChecksumRun.reason(e));
        } catch (CancellationException e) {
          found = null;
        }
      }

      @Override
      void finished(boolean completed) {
        if (completed) {
          counted(found, unread, error);
        }
      }
    }.start(workers, Workers.Kind.IO);
  }

  /** Reads an entry's real path and kind, and counts a folder's contents. */
  @WorkerThread
  static Found find(Path path, CancellationToken token) throws IOException {
    Path real = WorkspaceFiles.realUnlessLink(path);
    boolean folder = real != null && FileOperations.plainDirectory(path);
    return new Found(
        new BrowserPath(path, real), folder, folder ? FileOperations.count(path, token) : null);
  }

  /** Returns the entry rows' paths below the shown folder, without those under another. */
  private List<Path> outermost(List<WorkspaceNode> nodes) {
    List<Path> paths = new ArrayList<Path>();
    for (WorkspaceNode node : nodes) {
      if (WorkspaceFiles.entry(node) && files.below(node) && !paths.contains(node.path())) {
        paths.add(node.path());
      }
    }
    List<Path> outer = new ArrayList<Path>();
    for (Path path : paths) {
      boolean inner = false;
      for (Path other : paths) {
        inner |= !other.equals(path) && path.startsWith(other);
      }
      if (!inner) {
        outer.add(path);
      }
    }
    return outer;
  }

  private void counted(final List<Found> found, Path failed, String error) {
    if (token.isCanceled() || found == null) {
      return;
    }
    if (error != null) {
      view.showInfo(
          UiText.DELETE_TITLE.text(), UiText.DELETE_UNREADABLE.format(failed.getFileName(), error));
      return;
    }
    List<String> dirty = new ArrayList<String>();
    for (Found entry : found) {
      if (files.busy(entry.entry())) {
        return;
      }
      for (DocumentController window : files.windows()) {
        dirty.addAll(window.under().dirtyUnder(entry.entry()));
      }
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

  /** Returns the prompt: what goes, that it cannot be undone, the unsaved documents. */
  static String question(List<Found> found, List<String> dirty) {
    String text = found.size() == 1 ? one(found.get(0)) : several(found);
    if (dirty.isEmpty()) {
      return text;
    }
    return text + "\n\n" + UiText.DELETE_UNSAVED.format(listed(dirty));
  }

  private static String one(Found found) {
    String name = String.valueOf(found.entry().path().getFileName());
    if (found.folder()) {
      return UiText.DELETE_FOLDER_MESSAGE.format(
          name, found.counts().files(), found.counts().folders());
    }
    if (found.entry().real() == null) {
      return UiText.DELETE_LINK_MESSAGE.format(name);
    }
    return UiText.DELETE_FILE_MESSAGE.format(name);
  }

  private static String several(List<Found> found) {
    List<String> names = new ArrayList<String>();
    List<String> folders = new ArrayList<String>();
    long contentFiles = 0;
    long contentFolders = 0;
    boolean links = false;
    for (Found entry : found) {
      String name = String.valueOf(entry.entry().path().getFileName());
      names.add(name);
      if (entry.folder()) {
        folders.add(name);
        contentFiles += entry.counts().files();
        contentFolders += entry.counts().folders();
      } else if (entry.entry().real() == null) {
        links = true;
      }
    }
    String text = UiText.DELETE_MANY_MESSAGE.format(found.size()) + "\n\n" + listed(names);
    String contents = contents(contentFiles, contentFolders);
    if (contents != null) {
      text +=
          "\n\n"
              + (folders.size() == 1
                  ? UiText.DELETE_MANY_ONE_FOLDER.format(folders.get(0), contents)
                  : UiText.DELETE_MANY_FOLDERS.format(folders.size(), contents));
    }
    if (links) {
      text += "\n\n" + UiText.DELETE_MANY_LINKS.text();
    }
    return text;
  }

  /** Returns "N files and M folders", leaving out a zero part; null when both are zero. */
  static String contents(long files, long folders) {
    String fileText = UiText.count(files, UiText.COUNT_FILE, UiText.COUNT_FILES);
    String folderText = UiText.count(folders, UiText.COUNT_FOLDER, UiText.COUNT_FOLDERS);
    if (files == 0) {
      return folders == 0 ? null : folderText;
    }
    return folders == 0 ? fileText : UiText.COUNT_AND.format(fileText, folderText);
  }

  /** Returns names joined by commas, the first {@link #LISTED_NAMES}, then {@code +N}. */
  static String listed(List<String> names) {
    List<String> shown = names.subList(0, Math.min(LISTED_NAMES, names.size()));
    String text = String.join(", ", shown);
    if (names.size() > shown.size()) {
      text += ", +" + (names.size() - shown.size());
    }
    return text;
  }

  private void deleteNow(final List<Found> found) {
    new BusyWork(ui, view) {
      private final List<FileOperations.Deleted> results = new ArrayList<FileOperations.Deleted>();

      @Override
      @WorkerThread
      void work() {
        for (Found entry : found) {
          if (token.isCanceled()) {
            break;
          }
          results.add(FileOperations.delete(entry.entry().path(), token));
        }
      }

      @Override
      void finished(boolean completed) {
        if (completed) {
          deleted(found, results);
        }
      }
    }.start(workers, Workers.Kind.IO);
  }

  private void deleted(List<Found> found, List<FileOperations.Deleted> results) {
    List<String> failures = new ArrayList<String>();
    for (int i = 0; i < results.size(); i++) {
      String failure = settle(found.get(i), results.get(i));
      if (failure != null) {
        failures.add(failure);
      }
    }
    if (!failures.isEmpty()) {
      view.showInfo(UiText.DELETE_TITLE.text(), String.join("\n", failures));
    }
  }

  /**
   * Applies one finished delete; returns why it stopped, or null when it finished or was canceled.
   */
  private String settle(Found found, FileOperations.Deleted result) {
    for (DocumentController window : files.windows()) {
      window.under().deleted(found.entry());
    }
    if (result.done()) {
      workspace.following().deleted(found.entry());
    }
    Path path = found.entry().path();
    boolean folder = found.folder() || found.entry().real() == null;
    workspace.changed(path.getParent(), path, null, folder, null);
    if (result.done() || token.isCanceled()) {
      return null;
    }
    return UiText.DELETE_FAILED.format(
        path.getFileName(),
        result.failed(),
        LogText.oneLine(result.error()),
        result.remaining().files(),
        result.remaining().folders());
  }
}
