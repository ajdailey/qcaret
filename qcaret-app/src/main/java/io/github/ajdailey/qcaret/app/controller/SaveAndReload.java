package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.DocumentSnapshot;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import io.github.ajdailey.qcaret.core.document.LoadTooLargeException;
import io.github.ajdailey.qcaret.core.document.ReloadedFile;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.io.ExternalModificationException;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.EditorConfig;
import io.github.ajdailey.qcaret.core.text.FileSettings;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Save, Save As and reload of one window's documents, and what a file check reports about them: the
 * writes and reads on {@code qcaret-io-} workers, the baseline check, the save conflict and change
 * prompts, and the missing mark. {@link DocumentController} routes commands here and keeps the
 * documents. A save and a reload of one document refuse each other; {@link DocumentsUnder} refuses
 * both while a rename holds the file, and {@link DocumentSettings#prepareSave} runs the save
 * cleanup before the write. Confined to the UI thread.
 */
final class SaveAndReload {
  private static final Logger LOG = LoggerFactory.getLogger(SaveAndReload.class);

  /** What the save and reload workflows reach of their window and the other windows. */
  interface Window {
    /** A save gave the document another file name. */
    void renamed(OpenDocument d);

    /** Returns the tab of a file other than {@code d}, in any window, or null. */
    OpenDocument holderOfFile(OpenDocument d, Path real);

    /**
     * Closes a document's tab in the window holding it, then runs {@code ended}; runs it at once
     * when no window holds the document.
     *
     * @param held the document
     * @param ask true to ask Save, Don't Save or Cancel first; a Cancel also runs {@code ended}
     * @param ended runs once the tab closed or stayed
     */
    void closeHeld(Document held, boolean ask, Runnable ended);

    /** Returns the window's documents as the workspace browser's Rename and Delete reach them. */
    DocumentsUnder under();

    /** A save left the buffer clean. */
    void cleaned(String bufferId);

    /** Returns the targets of the writes running in every window. */
    RunningWrites writes();
  }

  /**
   * How a save ends; a save runs exactly one of the two.
   *
   * @param saved runs once the file is written, or null
   * @param stopped runs when no file was written, or null
   */
  record SaveEnd(Runnable saved, Runnable stopped) {
    static final SaveEnd NONE = new SaveEnd(null, null);

    void finish() {
      if (saved != null) {
        saved.run();
      }
    }

    void stop() {
      if (stopped != null) {
        stopped.run();
      }
    }
  }

  /** Reloads retried while the document changes under them. */
  static final int RELOAD_ATTEMPTS = 3;

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
  private final Window window;
  private CompareController compare;
  private boolean autoReloadClean = true;

  /**
   * Creates the workflows of one window.
   *
   * @param workers runs the writes and reads
   * @param ui receives their results
   * @param view the window
   * @param budget the documents' heap share, which a reload charges
   * @param options how files load
   * @param changes the window's file checks
   * @param declared the per-file settings
   * @param window the window's documents
   */
  SaveAndReload(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      MemoryBudget budget,
      LoadOptions options,
      ExternalChanges changes,
      DocumentSettings declared,
      Window window) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.budget = budget;
    this.options = options;
    this.changes = changes;
    this.declared = declared;
    this.window = window;
  }

  /** Sets the controller that shows the prompts' Compare. */
  void setCompare(CompareController value) {
    compare = value;
  }

  /**
   * Sets whether a changed file reloads a clean buffer without a prompt.
   *
   * @param on false to prompt for every change
   */
  void setAutoReloadClean(boolean on) {
    autoReloadClean = on;
  }

  /** Saves a document; an untitled one asks for a file. */
  void save(OpenDocument d, SaveEnd end) {
    if (binaryNotice(d) || noRoundTripNotice(d)) {
      end.stop();
      return;
    }
    if (d.file.path == null) {
      saveAs(d, end);
    } else {
      Path file = d.file.target();
      write(d, file, file, d.file.baseline, end);
    }
  }

  /** Saves a document to a file the user picks. */
  void saveAs(final OpenDocument d, final SaveEnd end) {
    if (binaryNotice(d) || savingNotice(d)) {
      end.stop();
      return;
    }
    Path directory = d.file.path == null ? null : d.file.path.getParent();
    view.chooseSaveTarget(
        directory,
        d.name(),
        new Consumer<Path>() {
          @Override
          public void accept(Path target) {
            if (target == null || d.closed) {
              end.stop();
            } else {
              saveAsTarget(d, target, end);
            }
          }
        });
  }

  /** Resolves a Save As target's real path on a worker, then writes it. */
  private void saveAsTarget(final OpenDocument d, final Path target, final SaveEnd end) {
    final FileSettingsReader reader = declared.reader();
    final TextSnapshot text = d.document.text();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final Path real = realFile(target);
                final FileSettings found = reader == null ? null : reader.read(real, text);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        targetResolved(d, target, real, found, end);
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Save As stopped: the workers are closed");
      end.stop();
    }
  }

  /**
   * Writes a Save As target. When another tab holds the file, in any window, that tab closes once
   * the write succeeds; with unsaved changes it asks first, and a decline writes nothing. An edit
   * in that tab after the question asks about the tab again; a Cancel keeps it and the save still
   * ends as written.
   */
  private void targetResolved(
      final OpenDocument d,
      final Path target,
      Path real,
      final FileSettings found,
      final SaveEnd end) {
    if (d.closed) {
      end.stop();
      return;
    }
    final OpenDocument other = window.holderOfFile(d, real);
    if (other == null) {
      writeAs(d, target, found, end);
      return;
    }
    final Document held = other.document;
    final long seen = held.revision();
    final SaveEnd closeOther =
        new SaveEnd(
            new Runnable() {
              @Override
              public void run() {
                Runnable ended =
                    new Runnable() {
                      @Override
                      public void run() {
                        if (!d.closed) {
                          d.tab.select();
                        }
                        end.finish();
                      }
                    };
                window.closeHeld(held, held.isDirty() && held.revision() != seen, ended);
              }
            },
            end.stopped());
    if (!held.isDirty()) {
      writeAs(d, target, found, closeOther);
      return;
    }
    view.askReplaceOpenFile(
        other.name(),
        new Runnable() {
          @Override
          public void run() {
            if (d.closed) {
              end.stop();
            } else {
              writeAs(d, target, found, closeOther);
            }
          }
        },
        end::stop);
  }

  /** Writes a Save As target with the settings read for it, applied once the write succeeds. */
  private void writeAs(OpenDocument d, Path target, FileSettings found, SaveEnd end) {
    d.settings.pending = found;
    write(d, target, d.file.path, d.file.baseline, end);
  }

  /** Returns a file's real path, or its absolute normalized path when it does not exist. */
  @WorkerThread
  static Path realFile(Path file) {
    try {
      return file.toRealPath();
    } catch (IOException e) {
      return file.toAbsolutePath().normalize();
    }
  }

  /** Shows the notice and returns true for a binary file's view, which is never written. */
  private boolean binaryNotice(OpenDocument d) {
    if (!d.binary) {
      return false;
    }
    view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_BINARY.format(d.name()));
    return true;
  }

  /** Shows the notice and returns true when a Save would rewrite bytes opened read-only. */
  private boolean noRoundTripNotice(OpenDocument d) {
    if (!refusesSave(d)) {
      return false;
    }
    view.showInfo(
        UiText.SAVE_TITLE.text(),
        UiText.SAVE_NO_ROUND_TRIP.format(d.name(), d.document.encoding().charset().displayName()));
    return true;
  }

  /** Reports whether Save refuses a document opened read-only for its bytes. */
  static boolean refusesSave(OpenDocument d) {
    return d.file.bytesGuarded && d.document.isReadOnly();
  }

  /** Shows the notice and returns true when a save or a reload of the document is running. */
  private boolean savingNotice(OpenDocument d) {
    if (d.file.reloading != null) {
      view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_RELOADING.format(d.name()));
      return true;
    }
    if (d.file.saving == null) {
      return false;
    }
    view.showInfo(UiText.SAVE_TITLE.text(), UiText.SAVE_RUNNING.format(d.name()));
    return true;
  }

  /**
   * Cancels a document's running save; a running Save a Copy As goes on ({@link SaveCopy#cancel}).
   */
  void cancelSave(OpenDocument d) {
    if (d.file.saving != null) {
      d.file.saving.cancel();
      d.file.saving = null;
    }
  }

  /**
   * Writes a snapshot on a worker; on success records the stamp and runs {@code then}. The baseline
   * guards the write only when the target's real path is {@code known}, the file it was taken from.
   */
  private void write(
      final OpenDocument d,
      final Path target,
      final Path known,
      final FileStamp baseline,
      final SaveEnd end) {
    if (d.binary || savingNotice(d) || window.under().refusesSave(d.file, d.name(), target)) {
      d.settings.pending = null;
      end.stop();
      return;
    }
    if (prepareSave(d, target, known, baseline, end)) {
      return;
    }
    d.document.clearPreedit();
    final DocumentSnapshot snapshot = d.document.snapshot();
    final CancellationToken token = new CancellationToken();
    final RunningWrites writes = window.writes();
    d.file.saving = token;
    if (d.file.watch != null) {
      changes.pause(d.file.watch);
    }
    view.setActive();
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                FileBaseline stamp = null;
                IOException error = null;
                Path written = target;
                // A canceled in-place write runs to its end. The next save of the file waits for
                // it, and its result reaches the UI thread after that write's result.
                d.file.writing.lock();
                try {
                  try {
                    FileStamp expected = sameFile(target, known) ? baseline : null;
                    stamp = DocumentFiles.save(target, snapshot, expected, token);
                    try {
                      written = target.toRealPath();
                    } catch (IOException e) {
                      // Saved; the path as named stands.
                    }
                  } catch (IOException e) {
                    error = e;
                  } catch (CancellationException e) {
                    // Canceled: the target is unchanged.
                  } finally {
                    final FileBaseline saved = stamp;
                    final IOException failure = error;
                    final Path file = written;
                    ui.asyncExec(
                        new Runnable() {
                          @Override
                          public void run() {
                            writes.ended(target);
                            saved(d, token, target, file, snapshot, saved, failure, end);
                          }
                        });
                    // After the post: an idle view has no result on its way.
                    view.setIdle();
                  }
                } finally {
                  d.file.writing.unlock();
                }
              }
            });
    if (started) {
      writes.started(target);
    } else {
      d.file.saving = null;
      d.settings.pending = null;
      resumeWatch(d);
      view.setIdle();
      end.stop();
    }
  }

  /** Starts the save cleanup; true when the write continues from its result. */
  private boolean prepareSave(
      final OpenDocument d,
      final Path target,
      final Path known,
      final FileStamp baseline,
      final SaveEnd end) {
    return declared.prepareSave(
        d,
        new Runnable() {
          @Override
          public void run() {
            write(d, target, known, baseline, end);
          }
        },
        end::stop);
  }

  /** Reports whether {@code path} resolves to {@code real}; false for a missing file or no real. */
  @WorkerThread
  private static boolean sameFile(Path path, Path real) {
    if (real == null) {
      return false;
    }
    if (path.equals(real)) {
      return true;
    }
    try {
      return path.toRealPath().equals(real);
    } catch (IOException e) {
      return false;
    }
  }

  private void saved(
      OpenDocument d,
      CancellationToken token,
      Path named,
      Path target,
      DocumentSnapshot snapshot,
      FileBaseline stamp,
      IOException error,
      SaveEnd end) {
    if (d.file.saving == token) {
      d.file.saving = null;
    }
    FileSettings pending = d.settings.pending;
    d.settings.pending = null;
    if (d.closed) {
      end.stop();
      return;
    }
    if (stamp != null) {
      if (pending != null) {
        declared.apply(d, pending);
      }
      boolean renamed = !target.equals(d.file.path);
      d.file.current(target, stamp.stamp());
      d.file.bytesGuarded = false;
      Path file = named.toAbsolutePath().normalize();
      d.file.given = file;
      if (d.file.watch == null) {
        watch(d, file, stamp);
      } else {
        changes.resume(d.file.watch, file, stamp);
      }
      if (renamed) {
        window.renamed(d);
      }
      d.document.markSaved(snapshot);
      if (d.file.forced != null) {
        d.file.forced = snapshot.encoding().charset();
      }
      if (!d.document.isDirty()) {
        window.cleaned(d.bufferId);
      }
      d.updateTitle();
      view.documentStateChanged();
      end.finish();
      return;
    }
    resumeWatch(d);
    if (error instanceof ExternalModificationException conflict) {
      if (conflict.current().equals(d.file.baseline)) {
        // The file holds what a canceled save of this document wrote in place.
        write(d, named, d.file.path, d.file.baseline, end);
      } else {
        askConflict(d, end);
      }
      return;
    }
    if (error != null) {
      view.showInfo(
          UiText.SAVE_TITLE.text(),
          UiText.SAVE_FAILED.format(d.name(), LogText.oneLine(error.getMessage())));
    }
    end.stop();
  }

  /** Starts the checks of a document's file at the path the user opened. */
  void watch(OpenDocument d, Path given, FileBaseline baseline) {
    d.file.given = given;
    d.file.watch = changes.watch(given, baseline);
  }

  /** Checks again after a save or a reload that did not change the baseline. */
  private void resumeWatch(OpenDocument d) {
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

  /** Asks what a save that found the file changed does next; Compare leaves the prompt open. */
  private void askConflict(final OpenDocument d, final SaveEnd end) {
    view.askExternalChange(
        d.name(),
        true,
        new Consumer<ChangeChoice>() {
          @Override
          public void accept(ChangeChoice choice) {
            if (d.closed) {
              if (choice != ChangeChoice.COMPARE) {
                end.stop();
              }
              return;
            }
            switch (choice) {
              case OVERWRITE -> {
                Path file = d.file.target();
                write(d, file, file, null, end);
              }
              case RELOAD -> {
                reload(d);
                end.stop();
              }
              case COMPARE -> compareWithDisk(d);
              default -> end.stop();
            }
          }
        });
  }

  private void compareWithDisk(OpenDocument d) {
    if (compare != null && d.file.watch != null) {
      compare.compareWithDisk(d.document, d.file.watch.path(), d.file.forced, d.binary);
    }
  }

  /**
   * Compares the file the user opened with the text on a {@code qcaret-io-} worker and replaces the
   * text between the common prefix and suffix as one undo step, keeping each view's caret by line
   * and column. The changed span stays reserved until it is applied. The document is clean
   * afterwards; undo returns to the text before the reload, dirty. A document edited during the
   * read reloads again, at most {@value #RELOAD_ATTEMPTS} times, unless the edit made a clean
   * document dirty: that edit stays and the user is asked. A failed read or a span the budget
   * refuses leaves the text; the watch takes the checked baseline, so the file is read again on a
   * new change, while the document's stamp stays and a save still finds the conflict. A charset the
   * user chose is forced; any other is detected, as an open does.
   */
  private void reload(final OpenDocument d) {
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
                  Path real = realFile(file);
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
      if (++d.file.reloadAttempts < RELOAD_ATTEMPTS) {
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
