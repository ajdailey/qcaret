package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import io.github.ajdailey.qcaret.core.document.LoadTooLargeException;
import io.github.ajdailey.qcaret.core.document.ReloadedFile;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.text.EditorConfig;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import io.github.ajdailey.qcaret.core.watch.FileCheck;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Reload and the change prompts of one window's documents for {@link SaveAndReload}: a file check's
 * result, Reload from Disk, the reload on a {@code qcaret-io-} worker, the missing mark and the
 * change prompt. A reload does not start while a save of the document runs. Confined to the UI
 * thread.
 */
final class FileReload {
  /** A reloaded file on its way to the UI thread. */
  private record Reloaded(
      Replacement replacement,
      TextEncoding encoding,
      LineEnding lineEnding,
      FileBaseline baseline,
      boolean roundTrips,
      Path real,
      LoadReservation reservation,
      EditorConfig.Result config) {}

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final MemoryBudget budget;
  private final LoadOptions options;
  private final ExternalChanges changes;
  private final DocumentSettings declared;
  private final SaveAndReload.Window window;
  private CompareController compare;
  private boolean autoReloadClean = true;

  FileReload(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget,
      LoadOptions options,
      ExternalChanges changes,
      DocumentSettings declared,
      SaveAndReload.Window window) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
    this.options = options;
    this.changes = changes;
    this.declared = declared;
    this.window = window;
  }

  void setCompare(CompareController value) {
    compare = value;
  }

  void setAutoReloadClean(boolean on) {
    autoReloadClean = on;
  }

  /** Starts the checks of a document's file at the path the user opened. */
  void watch(OpenDocument d, Path given, FileBaseline baseline) {
    d.file.given = given;
    d.file.watch = changes.watch(given, baseline);
  }

  /** Checks again after a save or a reload that did not change the baseline. */
  void resumeWatch(OpenDocument d) {
    if (d.file.watch != null) {
      changes.resume(d.file.watch, d.file.watch.path(), d.file.watch.baseline());
      d.file.watch.setMissing(d.file.missing);
    }
  }

  /** Acts on a check of a document's file; ignored while a save or a reload runs. */
  void externalChange(OpenDocument d, FileCheck.Result result) {
    if (d.closed || d.file.saving != null || d.file.reloading != null) {
      return;
    }
    switch (result.state()) {
      case UNCHANGED -> {
        FileBaseline old = d.file.watch.baseline();
        if (old != null && old.stamp().equals(d.file.baseline)) {
          d.file.baseline = result.current().stamp();
        }
        changes.accept(d.file.watch, result.current());
        if (d.file.missing) {
          clearMissing(d);
        }
      }
      case MISSING -> markMissing(d);
      case CHANGED -> {
        if (d.file.missing) {
          clearMissing(d);
        }
        if (autoReloadClean && !d.document.isDirty()) {
          d.file.reloadCheck = result.current();
          reload(d);
        } else {
          changes.accept(d.file.watch, result.current());
          askChange(d);
        }
      }
    }
  }

  /**
   * Reads a document's file again, as an external change does; a dirty document asks first. A file
   * known to be deleted shows a notice.
   */
  void reloadFromDisk(OpenDocument d) {
    if (d.file.watch == null || d.file.changePrompt) {
      return;
    }
    if (d.file.missing) {
      view.showNotice(UiText.CHANGE_MISSING.format(d.name()));
    } else if (d.document.isDirty()) {
      askReload(d);
    } else {
      reload(d);
    }
  }

  /** Asks before Reload from Disk replaces edits; one prompt per document at a time. */
  private void askReload(final OpenDocument d) {
    if (d.file.changePrompt) {
      return;
    }
    d.file.changePrompt = true;
    view.askReload(
        d.name(),
        new Runnable() {
          @Override
          public void run() {
            d.file.changePrompt = false;
            reload(d);
          }
        },
        new Runnable() {
          @Override
          public void run() {
            d.file.changePrompt = false;
          }
        });
  }

  /** Keeps the buffer of a deleted file, dirty, and remembers whether it matched the file. */
  private void markMissing(OpenDocument d) {
    if (d.file.missing) {
      return;
    }
    d.file.markMissing(d.document.isDirty() ? null : d.document.snapshot());
    d.document.markUnsaved();
    d.updateTitle();
    view.documentStateChanged();
    view.showNotice(UiText.CHANGE_MISSING.format(d.name()));
  }

  /** Clears the missing mark; the document is clean again when it still holds the file's text. */
  private void clearMissing(OpenDocument d) {
    DocumentSnapshot clean = d.file.clearMissing();
    if (clean != null && d.document.snapshot().state() == clean.state()) {
      d.document.markSaved(clean);
    }
    d.updateTitle();
    view.documentStateChanged();
  }

  /** Asks about a change of a dirty document's file; one prompt per document at a time. */
  private void askChange(final OpenDocument d) {
    if (d.file.changePrompt) {
      return;
    }
    d.file.changePrompt = true;
    view.askExternalChange(
        d.name(),
        false,
        new Consumer<ChangeChoice>() {
          @Override
          public void accept(ChangeChoice choice) {
            if (choice != ChangeChoice.COMPARE) {
              d.file.changePrompt = false;
            }
            if (d.closed) {
              return;
            }
            switch (choice) {
              case RELOAD -> reload(d);
              case COMPARE -> compareWithDisk(d);
              default -> {
                // Kept: the watch has the file's baseline, the save keeps the old one.
              }
            }
          }
        });
  }

  void compareWithDisk(OpenDocument d) {
    if (compare != null && d.file.watch != null) {
      compare.compareWithDisk(d.document, d.file.watch.path(), d.file.forced, d.binary);
    }
  }

  /**
   * Compares the file the user opened with the text on a {@code qcaret-io-} worker and replaces the
   * text between the common prefix and suffix as one undo step, keeping each view's caret by line
   * and column. The changed span stays reserved until it is applied. The document is clean
   * afterwards; undo returns to the text before the reload, dirty. A document edited during the
   * read reloads again, at most {@value SaveAndReload#RELOAD_ATTEMPTS} times, unless the edit made
   * a clean document dirty: that edit stays and the user is asked. A failed read or a span the
   * budget refuses leaves the text; the watch takes the checked baseline, so the file is read again
   * on a new change, while the document's stamp stays and a save still finds the conflict. A
   * charset the user chose is forced; any other is detected, as an open does.
   */
  void reload(final OpenDocument d) {
    if (window.under().refusesReload(d.file, d.name())) {
      return;
    }
    if (d.closed || d.file.watch == null || d.file.reloading != null || d.file.saving != null) {
      return;
    }
    final CancellationToken token = new CancellationToken();
    d.file.reloading = token;
    d.file.reloadOfClean = !d.document.isDirty();
    changes.pause(d.file.watch);
    d.document.clearPreedit();
    final DocumentSnapshot snapshot = d.document.snapshot();
    final Path file = d.file.watch.path();
    final Charset charset = d.file.forced;
    final boolean binary = d.binary;
    final LoadReservation reservation =
        LoadReservation.reload(budget, d.reserved, options.limits());
    final FileSettingsReader reader = binary ? null : declared.reader();
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                Reloaded result = null;
                IOException error = null;
                try {
                  Path real = SaveAndReload.realFile(file);
                  EditorConfig.Result config = reader == null ? null : reader.editorConfig(real);
                  LoadOptions with = reader == null ? options : reader.options(options, config);
                  ReloadedFile read =
                      CompareController.reload(
                          file, with, charset, binary, snapshot.text(), reservation, token);
                  result =
                      new Reloaded(
                          read.replacement(),
                          read.encoding(),
                          read.lineEnding(),
                          read.baseline(),
                          read.roundTrips(),
                          real,
                          reservation,
                          config);
                } catch (LoadTooLargeException e) {
                  // Reported as a reload without room: no result and no error.
                } catch (IOException e) {
                  error = e;
                } catch (CancellationException e) {
                  // Closed.
                } finally {
                  if (result == null) {
                    reservation.close();
                  }
                  final Reloaded reloaded = result;
                  final IOException failure = error;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          reloaded(d, token, snapshot.revision(), reloaded, failure);
                        }
                      });
                  view.setIdle();
                }
              }
            });
    if (!started) {
      d.file.reloading = null;
      d.file.reloadCheck = null;
      resumeWatch(d);
      view.setIdle();
    }
  }

  private void reloaded(
      OpenDocument d, CancellationToken token, long revision, Reloaded result, IOException error) {
    try {
      finishReload(d, token, revision, result, error);
    } finally {
      if (result != null) {
        result.reservation().close();
      }
    }
  }

  private void finishReload(
      OpenDocument d, CancellationToken token, long revision, Reloaded result, IOException error) {
    if (d.file.reloading != token) {
      return;
    }
    d.file.reloading = null;
    FileBaseline checked = d.file.reloadCheck;
    d.file.reloadCheck = null;
    if (d.closed) {
      return;
    }
    if (result == null) {
      d.file.reloadAttempts = 0;
      resumeWatch(d);
      if (error instanceof NoSuchFileException) {
        markMissing(d);
      } else if (error != null) {
        if (checked != null) {
          changes.accept(d.file.watch, checked);
        }
        view.showNotice(UiText.RELOAD_FAILED.format(d.name(), LogText.oneLine(error.getMessage())));
      } else if (!token.isCanceled()) {
        if (checked != null) {
          changes.accept(d.file.watch, checked);
        }
        view.showNotice(UiText.RELOAD_TOO_LARGE.format(d.name()));
      }
      return;
    }
    if (d.document.revision() != revision) {
      if (d.file.reloadOfClean && d.document.isDirty()) {
        d.file.reloadAttempts = 0;
        resumeWatch(d);
        if (checked == null) {
          askReload(d);
        } else {
          changes.accept(d.file.watch, checked);
          askChange(d);
        }
        return;
      }
      if (++d.file.reloadAttempts < SaveAndReload.RELOAD_ATTEMPTS) {
        d.file.reloadCheck = checked;
        reload(d);
        return;
      }
      d.file.reloadAttempts = 0;
      resumeWatch(d);
      view.showNotice(UiText.RELOAD_EDITED.format(d.name()));
      return;
    }
    d.file.reloadAttempts = 0;
    applyReload(d, result);
  }

  private void applyReload(OpenDocument d, Reloaded r) {
    Document document = d.document;
    if (r.replacement() != null) {
      int count = d.editors.size();
      int[] lines = new int[count];
      int[] columns = new int[count];
      TextSnapshot text = document.text();
      for (int i = 0; i < count; i++) {
        int caret = d.editors.get(i).selections().selections().primary().caret();
        lines[i] = text.lineAt(caret);
        columns[i] = caret - text.offsetOfLine(lines[i]);
      }
      boolean readOnly = document.isReadOnly();
      document.clearPreedit();
      document.setReadOnly(false);
      try {
        document.apply(
            EditBatch.of(EditBatch.Kind.COMMAND, List.of(r.replacement())),
            d.editor.selections(),
            null);
      } finally {
        document.setReadOnly(readOnly);
      }
      text = document.text();
      for (int i = 0; i < count; i++) {
        int line = Math.min(lines[i], text.lineCount() - 1);
        int offset = text.offsetOfLine(line) + Math.min(columns[i], text.lineLength(line));
        d.editors.get(i).selections().set(SelectionSet.caret(offset));
      }
    }
    if (!document.encoding().equals(r.encoding())) {
      document.setEncoding(r.encoding());
    }
    document.setLineEnding(r.lineEnding());
    document.markSaved(document.snapshot());
    d.file.current(r.real(), r.baseline().stamp());
    changes.resume(d.file.watch, d.file.watch.path(), r.baseline());
    if (r.roundTrips() || d.binary) {
      if (d.file.endGuard()) {
        document.setReadOnly(false);
      }
    } else if (!d.file.bytesGuarded || !document.isReadOnly()) {
      guardBytes(d);
      view.showNotice(UiText.OPEN_READ_ONLY.format(d.name(), r.encoding().charset().displayName()));
    }
    if (r.config() != null && declared.reader() != null) {
      declared.apply(d, declared.reader().settings(r.config(), document.text()));
    }
    d.updateTitle();
    view.documentStateChanged();
  }

  /** Makes a document read-only because a save would change its file's bytes. */
  void guardBytes(OpenDocument d) {
    d.file.guard(d.document.isReadOnly());
    d.document.clearPreedit();
    d.document.setReadOnly(true);
  }
}
