package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives a window that is not restored from the session the {@code workspace.default} folder. The
 * folder is checked and resolved to its real path on a {@code qcaret-io-} worker; the browser keeps
 * the visibility {@code ui.workspaceBrowser} gave it. A workspace set in the window meanwhile
 * stays. A missing, relative or non-directory folder leaves no workspace and logs one WARN per run.
 * Confined to the UI thread.
 */
final class WorkspaceDefault {
  private static final Logger LOG = LoggerFactory.getLogger(WorkspaceDefault.class);

  private final Settings settings;
  private final Workers workers;
  private final UiDispatcher ui;
  private boolean warned;
  private int finished;

  WorkspaceDefault(Settings settings, Workers workers, UiDispatcher ui) {
    this.settings = settings;
    this.workers = workers;
    this.ui = ui;
  }

  /** Sets the default folder as a window's workspace; does nothing while none is set. */
  void apply(final WorkspaceController workspace) {
    final String folder = settings.current().options().workspaceDefault();
    if (folder.isEmpty()) {
      return;
    }
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final Path real = folder(folder);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    applied(workspace, real);
                  }
                });
          }
        });
  }

  private void applied(WorkspaceController workspace, Path real) {
    finished++;
    if (real == null) {
      if (!warned) {
        warned = true;
        LOG.warn("Ignoring workspace.default: it is not an existing absolute folder");
      }
    } else if (workspace.root() == null) {
      workspace.restored(real, null, workspace.shown(), List.of());
    }
  }

  /** Returns how many checks have ended. */
  int finished() {
    return finished;
  }

  /** Returns the folder's real path; null for a relative, missing or non-directory path. */
  @WorkerThread
  private static Path folder(String folder) {
    try {
      Path path = Path.of(folder);
      return path.isAbsolute() && Files.isDirectory(path)
          ? WorkspaceController.realFolder(path)
          : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
