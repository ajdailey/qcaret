package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.search.FileReplace;
import io.github.ajdailey.qcaret.core.search.PatternTooComplexException;
import io.github.ajdailey.qcaret.core.search.ReplaceBackups;
import io.github.ajdailey.qcaret.core.search.ReplaceTemplate;
import io.github.ajdailey.qcaret.core.search.TextMatcher;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Applies a replace, or restores a replace run's backups, from a {@code qcaret-search-} worker. A
 * replace writes up to {@value #WRITERS} files on disk at once, each on a {@code qcaret-io-}
 * worker; a restore writes one after another. {@link #cancel} stops it between files: a file not
 * yet written is reported not attempted. Results queue on the worker; the UI thread moves them into
 * {@link #rows} at most every {@value #FLUSH_MILLIS} ms and calls {@link Listener#changed}, and
 * calls {@link Listener#finished} once.
 *
 * <ul>
 *   <li>An open document's replacements are computed on the worker from the snapshot taken when the
 *       run started, then applied on the UI thread by {@link Buffers}, one undo step per document.
 *       A file group whose file is open is edited the same way when the document's text, encoded,
 *       has the SHA-256 searched.
 *   <li>A file on disk goes through {@link FileReplace#replace}, its original kept by the run's
 *       {@link ReplaceBackups}, created with the first such file. Each run first deletes runs older
 *       than the days kept.
 *   <li>No undo spans files: Restore Run writes the backups back ({@link #restore}).
 * </ul>
 */
public final class ReplaceRun {
  /** Files on disk replaced at once. */
  public static final int WRITERS = 4;

  /** Least time between two moves of the queue into the rows. */
  public static final int FLUSH_MILLIS = 100;

  /** What happened to a file. */
  public enum Status {
    /** Replaced in, or restored. */
    CHANGED,
    /** Left as it was; {@link Row#reason} says why. */
    SKIPPED,
    /** A write or read failed; {@link Row#error} says how. */
    FAILED,
    /** The run stopped before it. */
    NOT_ATTEMPTED
  }

  /** Why a file was skipped. */
  public enum Reason {
    /** The file does not exist. */
    MISSING,
    /** The file or document differs from what was searched. */
    CHANGED,
    /** The file or document cannot be written. */
    READ_ONLY,
    /** The file could not be read. */
    UNREADABLE,
    /** Larger than the size limit. */
    TOO_LARGE,
    /** Binary. */
    BINARY,
    /** The bytes do not decode in the encoding searched with. */
    MALFORMED,
    /** The text does not encode back to the same bytes. */
    NO_ROUND_TRIP,
    /** No match is left to replace. */
    NO_MATCHES,
    /** A replacement has a char the encoding cannot write. */
    UNMAPPABLE,
    /** The memory budget refused the read. */
    NO_MEMORY,
    /** The document searched is closed and has no file to check. */
    CLOSED,
    /** The regex overflowed the stack. */
    TOO_COMPLEX,
    /** Restore: the file already holds its original bytes. */
    UNCHANGED,
    /** Restore: the file changed after the run. */
    CHANGED_SINCE
  }

  /**
   * One source to replace in.
   *
   * @param name the name the report shows
   * @param path the file's real path, or null for an untitled document
   * @param document the open document to edit, or null to write the file
   * @param snapshot the document's text when the run started, or null
   * @param revision the document's revision when the run started
   * @param ending the document's line ending, or null for a file
   * @param encoding the encoding searched with
   * @param digest the SHA-256 of the file searched, or null for a document group
   * @param spans the checked matches as ascending offset pairs
   * @param unavailable why the source is skipped before the run, or null
   */
  public record Item(
      String name,
      Path path,
      Document document,
      TextSnapshot snapshot,
      long revision,
      LineEnding ending,
      TextEncoding encoding,
      byte[] digest,
      int[] spans,
      Reason unavailable) {}

  /**
   * One file's outcome.
   *
   * @param name the name shown
   * @param path the file, or null for an untitled document
   * @param status what happened
   * @param reason why it was skipped, or null
   * @param replaced matches replaced
   * @param error the failure, or null
   */
  public record Row(
      String name, Path path, Status status, Reason reason, int replaced, String error) {}

  /** Edits open documents on the UI thread. */
  public interface Buffers {
    /**
     * Applies an item's replacements to its document as one undo step, unless the document closed,
     * changed since {@link Item#snapshot} or is read-only.
     *
     * @param item the item
     * @param replacements the replacements, in the snapshot's coordinates
     * @return the outcome
     */
    Row apply(Item item, List<Replacement> replacements);
  }

  /** Receives progress on the UI thread. */
  public interface Listener {
    /** Rows changed. */
    void changed();

    /** The run ended. */
    void finished();
  }

  private record Done(int index, Row row, Item item, List<Replacement> buffer) {}

  private final List<Row> rows = new ArrayList<Row>();
  private final UiDispatcher ui;
  private final Buffers buffers;
  private final Listener listener;
  private final CancellationToken token = new CancellationToken();
  private final ConcurrentLinkedQueue<Done> queue = new ConcurrentLinkedQueue<Done>();
  private final AtomicBoolean pending = new AtomicBoolean();
  private final Runnable flush =
      new Runnable() {
        @Override
        public void run() {
          flush();
          listener.changed();
        }
      };
  private volatile ReplaceBackups backups;
  private volatile boolean finished;

  private ReplaceRun(UiDispatcher ui, Buffers buffers, Listener listener) {
    this.ui = ui;
    this.buffers = buffers;
    this.listener = listener;
  }

  /**
   * Starts a replace. Call on the UI thread.
   *
   * @param items the sources, in report order
   * @param matcher the query
   * @param template the replacement
   * @param backupRoot the backups' directory, {@code <settings>/replace-backups}
   * @param backupDays the days a run's backups are kept
   * @param limits the largest file read
   * @param workers runs the replace
   * @param ui receives the results
   * @param budget charged while a file is replaced
   * @param buffers edits open documents
   * @param listener called on the UI thread
   * @param clock dates the backups and the prune before the run
   * @return the running replace
   */
  public static ReplaceRun replace(
      final List<Item> items,
      final TextMatcher matcher,
      final ReplaceTemplate template,
      final Path backupRoot,
      final int backupDays,
      final HyperSearch.Limits limits,
      final Workers workers,
      UiDispatcher ui,
      final MemoryBudget budget,
      Buffers buffers,
      Listener listener,
      final Clock clock) {
    final ReplaceRun run = new ReplaceRun(ui, buffers, listener);
    for (Item item : items) {
      run.rows.add(new Row(item.name(), item.path(), Status.NOT_ATTEMPTED, null, 0, null));
    }
    boolean started =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                run.replaceAll(
                    workers,
                    items,
                    matcher,
                    template,
                    backupRoot,
                    backupDays,
                    limits,
                    budget,
                    clock);
              }
            });
    if (!started) {
      run.finish();
    }
    return run;
  }

  /**
   * Starts restoring a replace run's backups. Call on the UI thread.
   *
   * @param directory the run's directory
   * @param workers runs the restore
   * @param ui receives the results
   * @param listener called on the UI thread
   * @return the running restore
   */
  public static ReplaceRun restore(
      final Path directory, Workers workers, UiDispatcher ui, Listener listener) {
    final ReplaceRun run = new ReplaceRun(ui, null, listener);
    boolean started =
        workers.tryStart(
            Workers.Kind.SEARCH,
            new Runnable() {
              @Override
              public void run() {
                RunRestore.restoreAll(run, directory);
              }
            });
    if (!started) {
      run.finish();
    }
    return run;
  }

  /**
   * Returns the rows: one per item for a replace, one per backed-up file once read for a restore.
   *
   * @return the rows, unmodifiable
   */
  public List<Row> rows() {
    return Collections.unmodifiableList(rows);
  }

  /**
   * Counts the rows with a status.
   *
   * @param status the status
   * @return the count
   */
  public int count(Status status) {
    int n = 0;
    for (Row row : rows) {
      n += row.status() == status ? 1 : 0;
    }
    return n;
  }

  /**
   * Reports whether the run ended.
   *
   * @return true once {@link Listener#finished} was called
   */
  public boolean isFinished() {
    return finished;
  }

  /**
   * Returns the backups of a replace that wrote a file.
   *
   * @return the backups, or null
   */
  public ReplaceBackups backups() {
    return backups;
  }

  /** Stops the run after the file in progress. */
  public void cancel() {
    token.cancel();
  }

  /** Returns the token {@link #cancel} cancels. */
  CancellationToken token() {
    return token;
  }

  /**
   * Reports whether {@link #cancel} was called.
   *
   * @return true once canceled
   */
  public boolean canceled() {
    return token.isCanceled();
  }

  @WorkerThread
  private void replaceAll(
      final Workers workers,
      List<Item> items,
      final TextMatcher matcher,
      final ReplaceTemplate template,
      Path backupRoot,
      int backupDays,
      final HyperSearch.Limits limits,
      final MemoryBudget budget,
      Clock clock) {
    final Semaphore writers = new Semaphore(WRITERS);
    try {
      ReplaceBackups.prune(backupRoot, backupDays, clock.instant());
      for (int i = 0; i < items.size() && !token.isCanceled(); i++) {
        final int index = i;
        final Item item = items.get(i);
        if (item.unavailable() != null) {
          queue(index, skipped(item, item.unavailable()));
          continue;
        }
        if (item.document() != null) {
          try {
            buffer(index, item, matcher, template);
          } catch (CancellationException e) {
            break;
          } catch (PatternTooComplexException e) {
            queue(index, skipped(item, Reason.TOO_COMPLEX));
          }
          continue;
        }
        if (backups == null) {
          try {
            backups = ReplaceBackups.start(backupRoot, clock.instant());
          } catch (IOException e) {
            queue(index, failed(item, LogText.oneLine(String.valueOf(e.getMessage()))));
            continue;
          }
        }
        if (!acquire(writers)) {
          break;
        }
        boolean started =
            workers.tryStart(
                Workers.Kind.IO,
                new Runnable() {
                  @Override
                  public void run() {
                    try {
                      file(index, item, matcher, template, limits, budget);
                    } finally {
                      writers.release();
                    }
                  }
                });
        if (!started) {
          writers.release();
          break;
        }
      }
    } finally {
      writers.acquireUninterruptibly(WRITERS);
      postFinish();
    }
  }

  /** Posts {@link #finish} to the UI thread. */
  void postFinish() {
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            finish();
          }
        });
  }

  /** Waits for a writer slot; false once canceled. */
  private boolean acquire(Semaphore writers) {
    while (!token.isCanceled()) {
      try {
        if (writers.tryAcquire(50, TimeUnit.MILLISECONDS)) {
          if (!token.isCanceled()) {
            return true;
          }
          writers.release();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** Computes an open document's replacements from its snapshot; the UI thread applies them. */
  @WorkerThread
  private void buffer(int index, Item item, TextMatcher matcher, ReplaceTemplate template) {
    if (item.digest() != null
        && !FileReplace.holds(item.snapshot(), item.encoding(), item.digest())) {
      queue(index, skipped(item, Reason.CHANGED));
      return;
    }
    List<Replacement> replacements =
        FileReplace.replacements(
            item.snapshot(), item.spans(), matcher, template, item.ending(), token);
    if (replacements.isEmpty()) {
      queue(index, skipped(item, Reason.NO_MATCHES));
    } else if (!FileReplace.encodable(replacements, item.encoding())) {
      queue(index, skipped(item, Reason.UNMAPPABLE));
    } else {
      queue.add(new Done(index, null, item, replacements));
      schedule();
    }
  }

  /** Replaces in a file on disk; a cancel before its write leaves it not attempted. */
  @WorkerThread
  private void file(
      int index,
      Item item,
      TextMatcher matcher,
      ReplaceTemplate template,
      HyperSearch.Limits limits,
      MemoryBudget budget) {
    FileReplace.Outcome outcome;
    try {
      outcome =
          FileReplace.replace(
              new FileReplace.Target(item.path(), item.digest(), item.encoding(), item.spans()),
              matcher,
              template,
              backups,
              limits.maxFileBytes(),
              budget,
              token);
    } catch (CancellationException e) {
      return;
    } catch (PatternTooComplexException e) {
      queue(index, skipped(item, Reason.TOO_COMPLEX));
      return;
    }
    if (outcome.changed()) {
      queue(
          index, new Row(item.name(), item.path(), Status.CHANGED, null, outcome.replaced(), null));
    } else if (outcome.skip() != null) {
      queue(index, skipped(item, Reason.valueOf(outcome.skip().name())));
    } else {
      queue(index, failed(item, LogText.oneLine(String.valueOf(outcome.error().getMessage()))));
    }
  }

  private static Row skipped(Item item, Reason reason) {
    return new Row(item.name(), item.path(), Status.SKIPPED, reason, 0, null);
  }

  private static Row failed(Item item, String error) {
    return new Row(item.name(), item.path(), Status.FAILED, null, 0, error);
  }

  /** Queues a row for the UI thread; an index below 0 appends it. */
  void queue(int index, Row row) {
    queue.add(new Done(index, row, null, null));
    schedule();
  }

  /** Arms the UI flush once per batch of results. */
  private void schedule() {
    if (pending.compareAndSet(false, true)) {
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              if (!finished) {
                ui.timerExec(FLUSH_MILLIS, flush);
              }
            }
          });
    }
  }

  /** Moves the queue into the rows, editing open documents; on the UI thread. */
  private void flush() {
    pending.set(false);
    Done next = queue.poll();
    while (next != null) {
      Row row = next.row();
      if (next.buffer() != null) {
        row = buffers.apply(next.item(), next.buffer());
      }
      if (next.index() < 0) {
        rows.add(row);
      } else {
        rows.set(next.index(), row);
      }
      next = queue.poll();
    }
  }

  private void finish() {
    if (finished) {
      return;
    }
    ui.cancelTimer(flush);
    flush();
    finished = true;
    listener.finished();
  }
}
