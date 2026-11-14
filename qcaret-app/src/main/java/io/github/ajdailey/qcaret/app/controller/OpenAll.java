package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Open All in File > Recent Files and File > Recently Closed. A {@code qcaret-io-} worker keeps the
 * listed paths that are readable regular files; the UI thread opens those in list order, selecting
 * a file already open, and names the others once in a status notice. A missing file is skipped
 * rather than opened as a new empty tab, as a single open of it does. A file whose load then fails
 * or is canceled is not reported as opened.
 */
final class OpenAll {
  private OpenAll() {}

  /**
   * Checks the files on a worker and opens the readable ones.
   *
   * @param workers runs the check
   * @param ui receives its result
   * @param view shows the notice
   * @param documents opens the files
   * @param paths the listed files, in list order
   * @param opened receives each file once its tab shows, on the UI thread, or null
   */
  static void open(
      Workers workers,
      final UiDispatcher ui,
      final MainView view,
      final DocumentController documents,
      List<Path> paths,
      final Consumer<Path> opened) {
    final List<Path> listed = List.copyOf(paths);
    new BusyWork(ui, view) {
      @Override
      @WorkerThread
      void work() {
        final List<Path> readable = new ArrayList<Path>();
        final List<String> skipped = new ArrayList<String>();
        for (Path path : listed) {
          if (Files.isRegularFile(path) && Files.isReadable(path)) {
            readable.add(path);
          } else {
            skipped.add(String.valueOf(path.getFileName()));
          }
        }
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                if (opened == null) {
                  documents.open(readable);
                } else {
                  for (final Path path : readable) {
                    documents.open(
                        path,
                        new Consumer<EditorController>() {
                          @Override
                          public void accept(EditorController editor) {
                            opened.accept(path);
                          }
                        });
                  }
                }
                if (!skipped.isEmpty()) {
                  view.showNotice(UiText.OPEN_ALL_SKIPPED.format(String.join(", ", skipped)));
                }
              }
            });
      }
    }.start(workers, Workers.Kind.IO);
  }
}
