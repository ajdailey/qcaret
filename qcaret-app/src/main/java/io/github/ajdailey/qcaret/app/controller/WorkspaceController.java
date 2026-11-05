package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * The window's workspace folder: one root, set with File > Set Workspace Folder and kept for the
 * run. HyperSearch's workspace scope searches its tree. Confined to the UI thread.
 */
public final class WorkspaceController {
  private final MainView view;
  private final Workers workers;
  private final UiDispatcher ui;
  private Path root;

  /**
   * Creates the controller without a folder.
   *
   * @param view asks for the folder
   * @param workers resolves the folder's real path
   * @param ui receives the real path
   */
  public WorkspaceController(MainView view, Workers workers, UiDispatcher ui) {
    this.view = view;
    this.workers = workers;
    this.ui = ui;
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
   * Resolves a folder's real path on a {@code qcaret-io-} worker, then makes it the workspace and
   * says so in the status bar.
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
                    root = real;
                    view.showNotice(UiText.WORKSPACE_SET.format(real));
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
}
