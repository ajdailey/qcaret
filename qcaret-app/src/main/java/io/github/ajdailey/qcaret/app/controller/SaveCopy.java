package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.text.FileSettings;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * File > Save a Copy As: writes a document's text, unsaved edits included, to a chosen file through
 * {@link DocumentFiles#save} on a {@code qcaret-io-} worker. The copy takes the document's
 * encoding, BOM and line breaks, and the save cleanup its declared settings ask for, applied to the
 * copy only. The document keeps its path, dirty state, undo history and file check. A target whose
 * real path is the document's file runs Save instead. Esc and a close cancel the write through
 * {@link SaveAndReload#cancelSave}. Confined to the UI thread.
 */
final class SaveCopy {
  /** What a copy reaches of its window and the other windows. */
  interface Window {
    /**
     * A copy was written: checks the tab holding the file, in any window, and lists it in Recent
     * Files.
     *
     * @param d the copied document
     * @param real the copy's real path
     */
    void copied(OpenDocument d, Path real);

    /** Returns the window's documents as the workspace browser's Rename and Delete reach them. */
    DocumentsUnder under();
  }

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final SaveAndReload files;
  private final Window window;

  SaveCopy(Workers workers, UiDispatcher ui, MainView view, SaveAndReload files, Window window) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.files = files;
    this.window = window;
  }

  /** Asks for a file and writes the document's text to it. */
  void saveCopy(final OpenDocument d) {
    if (refused(d)) {
      return;
    }
    Path directory = d.file.path == null ? null : d.file.path.getParent();
    view.chooseSaveTarget(
        directory,
        d.name(),
        new Consumer<Path>() {
          @Override
          public void accept(Path target) {
            if (target != null && !d.closed) {
              resolve(d, target);
            }
          }
        });
  }

  /** Shows the notice and returns true for a binary view or while a copy of it runs. */
  private boolean refused(OpenDocument d) {
    if (d.binary) {
      view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_BINARY.format(d.name()));
      return true;
    }
    if (d.file.copying != null) {
      view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_RUNNING.format(d.name()));
      return true;
    }
    return false;
  }

  /** Resolves the target's real path on a worker. */
  private void resolve(final OpenDocument d, final Path target) {
    workers.tryStart(
        Workers.Kind.IO,
        new Runnable() {
          @Override
          public void run() {
            final Path real = SaveAndReload.realFile(target);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    resolved(d, target, real);
                  }
                });
          }
        });
  }

  private void resolved(final OpenDocument d, final Path target, Path real) {
    if (d.closed) {
      return;
    }
    if (real.equals(d.file.path)) {
      files.save(d, SaveAndReload.SaveEnd.NONE);
      return;
    }
    if (refused(d) || window.under().refusesSave(d.file, d.name(), target)) {
      return;
    }
    d.document.clearPreedit();
    final DocumentSnapshot snapshot = d.document.snapshot();
    FileSettings settings = d.settings.settings;
    final FileSettings cleanup =
        !d.document.isReadOnly() && SaveCleanup.wanted(settings) ? settings : null;
    final LineEnding ending = DocumentSettings.cleanupEnding(d.document, settings);
    final CancellationToken token = new CancellationToken();
    d.file.copying = token;
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                Path written = null;
                IOException error = null;
                try {
                  DocumentFiles.save(
                      target, cleaned(snapshot, cleanup, ending, token), null, token);
                  written = SaveAndReload.realFile(target);
                } catch (IOException e) {
                  error = e;
                } catch (CancellationException e) {
                  // Canceled: the target is unchanged.
                } finally {
                  final Path file = written;
                  final IOException failure = error;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          written(d, token, target, file, failure);
                        }
                      });
                  view.setIdle();
                }
              }
            });
    if (!started) {
      d.file.copying = null;
      view.setIdle();
    }
  }

  /** Returns the snapshot with the save cleanup applied, or the snapshot when none applies. */
  @WorkerThread
  static DocumentSnapshot cleaned(
      DocumentSnapshot snapshot, FileSettings cleanup, LineEnding ending, CancellationToken token) {
    EditBatch edit =
        cleanup == null ? null : SaveCleanup.edits(snapshot.text(), cleanup, ending, token);
    if (edit == null) {
      return snapshot;
    }
    RopeStorage text = RopeStorage.from(snapshot.text());
    List<Replacement> replacements = edit.replacements();
    for (int i = replacements.size() - 1; i >= 0; i--) {
      Replacement r = replacements.get(i);
      text.replace(r.offset(), r.removeLength(), r.text());
    }
    return new DocumentSnapshot(
        snapshot.revision(),
        snapshot.state(),
        text.snapshot(),
        snapshot.encoding(),
        snapshot.lineEnding());
  }

  private void written(
      OpenDocument d, CancellationToken token, Path target, Path real, IOException error) {
    if (d.file.copying == token) {
      d.file.copying = null;
    }
    if (real != null) {
      window.copied(d, real);
      view.showNotice(UiText.SAVE_COPY_DONE.format(d.name(), target));
    } else if (error != null) {
      view.showInfo(
          UiText.SAVE_TITLE.text(),
          UiText.SAVE_FAILED.format(
              String.valueOf(target.getFileName()), LogText.oneLine(error.getMessage())));
    }
  }
}
