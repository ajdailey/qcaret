package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Opens the files of a drop and ignores its folders. A {@code qcaret-io-} worker, under the busy
 * cursor, leaves out every path that is a directory, following links; the rest, in drop order,
 * reach the open on the UI thread unless the window has closed meanwhile. A path that is missing or
 * not a regular file stays in, so the open reports why it fails. Confined to the UI thread.
 */
final class DroppedFiles {
  private final Workers workers;
  private final UiDispatcher ui;
  private final ActivityIndicator activity;
  private final BooleanSupplier closed;
  private final Consumer<List<Path>> open;

  /**
   * Creates the filter.
   *
   * @param workers runs the check
   * @param ui runs the open
   * @param activity shows the check as running
   * @param closed true once the window closes; the open is then dropped
   * @param open opens the files
   */
  DroppedFiles(
      Workers workers,
      UiDispatcher ui,
      ActivityIndicator activity,
      BooleanSupplier closed,
      Consumer<List<Path>> open) {
    this.workers = workers;
    this.ui = ui;
    this.activity = activity;
    this.closed = closed;
    this.open = open;
  }

  /**
   * Checks the paths on a worker and opens the files among them.
   *
   * @param paths the dropped paths
   */
  void open(final List<Path> paths) {
    if (paths.isEmpty()) {
      return;
    }
    new BusyWork(ui, activity) {
      @Override
      @WorkerThread
      void work() {
        final List<Path> files = files(paths);
        if (files.isEmpty()) {
          return;
        }
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                if (!closed.getAsBoolean()) {
                  open.accept(files);
                }
              }
            });
      }
    }.start(workers, Workers.Kind.IO);
  }

  /**
   * Returns the paths that are not directories.
   *
   * @param paths the dropped paths
   * @return the rest, in order
   */
  @WorkerThread
  static List<Path> files(List<Path> paths) {
    List<Path> files = new ArrayList<Path>(paths.size());
    for (Path path : paths) {
      if (!Files.isDirectory(path)) {
        files.add(path);
      }
    }
    return files;
  }
}
