package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.app.search.ReplaceRun.Reason;
import io.github.ajdailey.qcaret.app.search.ReplaceRun.Row;
import io.github.ajdailey.qcaret.app.search.ReplaceRun.Status;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * The restore half of {@link ReplaceRun#restore}: writes a run's backups back one after another and
 * queues one row per backed-up file, then posts the run's finish.
 */
final class RunRestore {
  private RunRestore() {}

  /** Restores every backup of {@code directory} into {@code run}'s rows. */
  @WorkerThread
  static void restoreAll(ReplaceRun run, Path directory) {
    CancellationToken token = run.token();
    try {
      List<ReplaceBackups.Entry> entries;
      try {
        entries = ReplaceBackups.entries(directory);
      } catch (IOException e) {
        run.queue(
            -1,
            new Row(
                String.valueOf(directory.getFileName()),
                directory,
                Status.FAILED,
                null,
                0,
                LogText.oneLine(String.valueOf(e.getMessage()))));
        return;
      }
      for (ReplaceBackups.Entry entry : entries) {
        Path file = entry.file();
        String name = String.valueOf(file.getFileName());
        if (token.isCanceled()) {
          run.queue(-1, new Row(name, file, Status.NOT_ATTEMPTED, null, 0, null));
          continue;
        }
        ReplaceBackups.Restored restored = ReplaceBackups.restore(directory, entry, token);
        Row row =
            switch (restored.result()) {
              case RESTORED -> new Row(name, file, Status.CHANGED, null, 0, null);
              case UNCHANGED -> new Row(name, file, Status.SKIPPED, Reason.UNCHANGED, 0, null);
              case CHANGED_SINCE ->
                  new Row(name, file, Status.SKIPPED, Reason.CHANGED_SINCE, 0, null);
              case MISSING -> new Row(name, file, Status.SKIPPED, Reason.MISSING, 0, null);
              case FAILED ->
                  new Row(
                      name,
                      file,
                      Status.FAILED,
                      null,
                      0,
                      LogText.oneLine(String.valueOf(restored.error().getMessage())));
            };
        run.queue(-1, row);
      }
    } catch (CancellationException e) {
      // Stopped inside a write; the file is unchanged.
    } finally {
      run.postFinish();
    }
  }
}
