package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ChecksumDisplay;
import io.github.ajdailey.qcaret.app.view.ChecksumInput;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.ContentWriter;
import io.github.ajdailey.qcaret.core.io.SafeSave;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * One checksum list dialog's controller: the shown lines, the running run, Copy, Save As and
 * Cancel. A run's {@link #work} runs on a {@code qcaret-io-} worker and posts lines and progress
 * through a {@link Feed}; a post from a canceled or replaced run is dropped. Save As writes the
 * lines in UTF-8 with LF breaks through {@link SafeSave} on a {@code qcaret-io-} worker, and
 * refuses a target under a folder a workspace browser rename holds; a tab holding the written file,
 * in any window, is checked at once, as Save a Copy As does. Confined to the UI thread except
 * {@link #work}.
 *
 * @param <R> what a run reads, taken on the UI thread; immutable
 */
abstract class ChecksumRun<R> implements ChecksumInput {
  final Workers workers;
  final UiDispatcher ui;
  final MainView view;
  private final DocumentsUnder under;
  private final RunningWrites writes;
  private final Consumer<Path> written;
  private final List<String> lines = new ArrayList<String>();
  private ChecksumDisplay display;
  private CancellationToken running;
  private Path folder;

  /** {@code written} receives a saved list's real path, to check a tab holding it. */
  ChecksumRun(
      Workers workers,
      UiDispatcher ui,
      MainView view,
      DocumentsUnder under,
      RunningWrites writes,
      Consumer<Path> written) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.under = under;
    this.writes = writes;
    this.written = written;
  }

  /**
   * Opens the dialog.
   *
   * @param title the dialog title
   * @param note shown above the lines, or empty
   * @param chooseLabel the Choose button's label
   */
  final void openDialog(String title, String note, String chooseLabel) {
    display = view.showChecksums(title, note, chooseLabel, this);
  }

  /** Returns the open dialog, or null once it closed. */
  final ChecksumDisplay display() {
    return display;
  }

  /** Returns the name Save As offers. */
  abstract String saveName();

  /**
   * Runs a request on the worker.
   *
   * @param request what the run reads
   * @param feed posts lines, progress and the status line
   */
  @WorkerThread
  abstract void work(R request, Feed feed);

  /**
   * Starts a run on a {@code qcaret-io-} worker, canceling a running one and emptying the list.
   *
   * @param source shown above the lines
   * @param where the folder Save As and Choose start in
   * @param request what {@link #work} reads
   */
  final void start(String source, Path where, final R request) {
    if (display == null) {
      return;
    }
    cancel();
    lines.clear();
    folder = where;
    display.clear(source);
    final Feed feed = new Feed(new CancellationToken(), ui, this);
    final ChecksumRun<R> owner = this;
    running = feed.token;
    try {
      workers.start(
          Workers.Kind.IO,
          new Runnable() {
            @Override
            public void run() {
              feed.run(owner, request);
            }
          });
    } catch (RejectedExecutionException e) {
      running = null;
    }
  }

  /** Returns the folder of the last run's files or list, or null before a run. */
  final Path folder() {
    return folder;
  }

  @Override
  public final void copy() {
    if (!lines.isEmpty()) {
      view.setText(String.join("\n", lines) + "\n");
    }
  }

  @Override
  public final void saveAs() {
    if (lines.isEmpty()) {
      return;
    }
    final List<String> saved = List.copyOf(lines);
    view.chooseSaveTarget(
        folder,
        saveName(),
        new Consumer<Path>() {
          @Override
          public void accept(Path target) {
            if (target == null) {
              return;
            }
            if (under.holds(target)) {
              view.showNotice(UiText.CHECKSUM_SAVE_RENAMING.text());
            } else {
              save(target, saved);
            }
          }
        });
  }

  private void save(final Path target, final List<String> saved) {
    try {
      workers.start(
          Workers.Kind.IO,
          new Runnable() {
            @Override
            public void run() {
              String reason = null;
              Path real = null;
              try {
                reason = write(target, saved);
                if (reason == null) {
                  real = SaveAndReload.realFile(target);
                }
              } finally {
                final String failure = reason;
                final Path file = real;
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        writes.ended(target);
                        if (file != null) {
                          written.accept(file);
                        }
                        if (failure != null) {
                          view.showNotice(UiText.CHECKSUM_SAVE_FAILED.format(failure));
                        }
                      }
                    });
              }
            }
          });
      writes.started(target);
    } catch (RejectedExecutionException e) {
      // Shutting down.
    }
  }

  /**
   * Writes lines in UTF-8, each ended by LF; returns why it failed, or null. A failed write leaves
   * the target as it was, except where {@link SafeSave} writes in place.
   */
  @WorkerThread
  private static String write(Path target, List<String> saved) {
    StringBuilder text = new StringBuilder();
    for (String line : saved) {
      text.append(line).append('\n');
    }
    final byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
    try {
      SafeSave.save(
          target,
          null,
          new ContentWriter() {
            @Override
            public void write(OutputStream out) throws IOException {
              out.write(bytes);
            }
          },
          new CancellationToken());
      return null;
    } catch (IOException e) {
      return reason(e);
    }
  }

  @Override
  public final void cancel() {
    if (running != null) {
      running.cancel();
      running = null;
      if (display != null) {
        display.showStatus(UiText.CHECKSUM_CANCELED.text());
      }
    }
  }

  @Override
  public final void closed() {
    if (running != null) {
      running.cancel();
      running = null;
    }
    display = null;
  }

  /**
   * Returns a short reason a file operation failed.
   *
   * @param e the failure
   * @return for example {@code access denied} or the platform's reason
   */
  static String reason(Exception e) {
    if (e instanceof AccessDeniedException) {
      return "access denied";
    }
    if (e instanceof FileSystemException f && f.getReason() != null) {
      return f.getReason();
    }
    if (e instanceof InvalidPathException) {
      return "not a valid path";
    }
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private void added(CancellationToken token, List<String> batch) {
    if (token == running && display != null) {
      lines.addAll(batch);
      display.addLines(batch);
    }
  }

  private void progress(CancellationToken token, String text, int percent) {
    if (token == running && display != null) {
      display.showProgress(text, percent);
    }
  }

  private void finished(CancellationToken token, String status) {
    if (token == running && display != null) {
      running = null;
      display.showStatus(status);
    }
  }

  /**
   * A run's route from its worker to the dialog. Lines go in batches of up to {@value #BATCH};
   * progress goes when its whole percent or its file changes. Used by one worker.
   */
  static final class Feed {
    /** Lines posted at a time. */
    static final int BATCH = 64;

    final CancellationToken token;
    private final UiDispatcher ui;
    private final ChecksumRun<?> owner;
    private List<String> pending = new ArrayList<String>();
    private String lastName;
    private int lastPercent = -1;

    Feed(CancellationToken token, UiDispatcher ui, ChecksumRun<?> owner) {
      this.token = token;
      this.ui = ui;
      this.owner = owner;
    }

    /**
     * Runs a request, then posts its last lines. A cancel ends it without a status line; an
     * unexpected exception ends it with one and goes on to the workers' failure handler.
     */
    @WorkerThread
    <T> void run(ChecksumRun<T> run, T request) {
      boolean ended = false;
      try {
        run.work(request, this);
        ended = true;
      } catch (CancellationException e) {
        pending.clear();
        ended = true;
      } finally {
        flush();
        if (!ended) {
          finish(UiText.CHECKSUM_RUN_FAILED.text());
        }
      }
    }

    /**
     * Adds a line.
     *
     * @param line the line
     */
    void line(String line) {
      pending.add(line);
      if (pending.size() >= BATCH) {
        flush();
      }
    }

    /**
     * Reports the progress; posts when the percent or the file changes.
     *
     * @param name the file being read
     * @param done the bytes read in every file
     * @param total the bytes of every file
     */
    void progress(final String name, long done, long total) {
      final int percent = (int) Math.min(100, done * 100 / Math.max(1, total));
      if (percent == lastPercent && name.equals(lastName)) {
        return;
      }
      lastPercent = percent;
      lastName = name;
      flush();
      final String text = UiText.CHECKSUM_HASHING.format(name, percent);
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              owner.progress(token, text, percent);
            }
          });
    }

    /**
     * Posts the remaining lines and the status line.
     *
     * @param status the totals or a message
     */
    void finish(final String status) {
      flush();
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              owner.finished(token, status);
            }
          });
    }

    private void flush() {
      if (pending.isEmpty()) {
        return;
      }
      final List<String> batch = pending;
      pending = new ArrayList<String>();
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              owner.added(token, batch);
            }
          });
    }
  }
}
