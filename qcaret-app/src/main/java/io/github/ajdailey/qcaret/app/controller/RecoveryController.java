package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.recovery.RecoveryFile;
import io.github.ajdailey.qcaret.app.recovery.RecoveryStore;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.RecoveryInput;
import io.github.ajdailey.qcaret.app.view.RecoveryRow;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.DocumentFiles;
import io.github.ajdailey.qcaret.core.document.LoadCost;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadReservation;
import io.github.ajdailey.qcaret.core.document.LoadTooLargeException;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import io.github.ajdailey.qcaret.core.watch.FileCheck;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recovery files for the buffers of every window, through {@link RecoveryStore}; one instance
 * directory and one lock per {@link WindowSet}. Every {@code recovery.intervalSeconds} the UI
 * thread takes a snapshot of each dirty buffer whose revision changed and queues its write; a save,
 * a close and an undo back to the saved text queue its delete. The queue runs in order on {@code
 * qcaret-io-} workers under {@link #runLock}, so a write cannot land after the delete that follows
 * it. A buffer id is unique in the instance and a moved tab keeps it, so a move leaves the buffer's
 * file and written revision as they are.
 *
 * <p>A revision counts as written only once its file is on disk: a failed write leaves it
 * unwritten, so the next interval retries, with one notice until a write succeeds.
 *
 * <p>{@link #open} takes the instance's lock; {@link #start} lists dead instances' buffers in the
 * recovery dialog, shown by the window activated last. A recovered buffer opens in the window
 * holding its file, else in the window activated last. A restored buffer's old file goes only after
 * its new file is written, or when the buffer needs no recovery any more. {@link #quitting} and
 * {@link #finish} delete the instance directory, or write the remaining dirty buffers and keep it
 * when the process ends without an ordered quit. Confined to the UI thread apart from the queue.
 */
final class RecoveryController implements RecoveryInput {
  private static final Logger LOG = LoggerFactory.getLogger(RecoveryController.class);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

  /**
   * Instance directories opened in this process. The directory name ends with the count, so two
   * window sets of one JVM that open within one clock tick take different directories; a set given
   * the other's directory skips it in {@code claimDead} as its own.
   */
  private static final AtomicLong OPENED = new AtomicLong();

  /** One buffer in the dialog. */
  private static final class Row {
    RecoveryStore.Entry entry;
    boolean reading;

    Row(RecoveryStore.Entry entry) {
      this.entry = entry;
    }
  }

  private final WindowSet set;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MemoryBudget budget;
  private final Path settings;
  private final Clock clock;
  private final LoadOptions options = LoadOptions.DEFAULTS;
  private final Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();

  /** The revision each buffer's file holds. */
  private final Map<String, Long> written = new HashMap<String, Long>();

  /** The revision of each buffer's write in the queue. */
  private final Map<String, Long> queued = new HashMap<String, Long>();

  /** The buffers that may have a file. */
  private final Set<String> tracked = new HashSet<String>();

  /** Restored buffers' old files, kept until the new file is written. */
  private final Map<String, Path> replaced = new HashMap<String, Path>();

  private final Map<String, String> created = new HashMap<String, String>();

  /** Buffers whose file a rename moved; the next interval writes them with the new path. */
  private final Set<String> renamed = new HashSet<String>();

  private final List<Row> rows = new ArrayList<Row>();
  private final Consumer<String> bufferCleaned =
      new Consumer<String>() {
        @Override
        public void accept(String id) {
          cleaned(id);
        }
      };
  private final Runnable timer =
      new Runnable() {
        @Override
        public void run() {
          tick();
        }
      };

  /** Serializes the queue's tasks; the UI thread never takes it. */
  private final ReentrantLock runLock = new ReentrantLock();

  /** Guarded by {@link #runLock}. */
  private RecoveryStore store;

  /** Guarded by {@link #runLock}. */
  private final List<RecoveryStore.Claim> claims = new ArrayList<RecoveryStore.Claim>();

  /** Guarded by {@link #runLock}. */
  private boolean writeFailed;

  private int intervalSeconds;
  private int keepDays;
  private MainView dialog;
  private boolean started;
  private boolean closing;
  private volatile boolean keep;
  private final List<Runnable> afterOffer = new ArrayList<Runnable>();
  private boolean offerEnded;

  RecoveryController(
      WindowSet set,
      Workers workers,
      UiDispatcher ui,
      MemoryBudget budget,
      Path settings,
      int intervalSeconds,
      int keepDays,
      Clock clock) {
    this.set = set;
    this.workers = workers;
    this.ui = ui;
    this.budget = budget;
    this.settings = settings;
    this.intervalSeconds = intervalSeconds;
    this.keepDays = keepDays;
    this.clock = clock;
  }

  /** Follows a new window's buffers. */
  void attach(DocumentController documents) {
    documents.setBufferCleaned(bufferCleaned);
    documents.setBufferMoved(this::renamed);
  }

  private void renamed(String id) {
    if (tracked.contains(id)) {
      renamed.add(id);
    }
  }

  private void notice(String text) {
    ApplicationController window = set.target();
    if (window != null) {
      window.showNotice(text);
    }
  }

  /** Takes the instance's lock; a restored buffer's file needs it before the session ends. */
  void open() {
    submit(
        new Runnable() {
          @Override
          public void run() {
            openStore();
          }
        });
  }

  /** Offers dead instances' buffers and starts the interval. */
  void start() {
    if (started) {
      return;
    }
    started = true;
    final long oldest = clock.millis() - TimeUnit.DAYS.toMillis(keepDays);
    submit(
        new Runnable() {
          @Override
          public void run() {
            claimDead(oldest);
          }
        });
    schedule();
  }

  /** Sets {@code recovery.intervalSeconds}; the next write comes one interval from now. */
  void setIntervalSeconds(int seconds) {
    intervalSeconds = seconds;
    if (started && !closing) {
      ui.cancelTimer(timer);
      schedule();
    }
  }

  /** Sets {@code recovery.keepDays}, which the next start applies. */
  void setKeepDays(int days) {
    keepDays = days;
  }

  private void schedule() {
    ui.timerExec((int) TimeUnit.SECONDS.toMillis(intervalSeconds), timer);
  }

  private void tick() {
    if (!closing) {
      write(set.dirtyBuffers());
      schedule();
    }
  }

  /** Writes the buffers whose revision changed and deletes the files of the others. */
  void write(List<DocumentController.Buffer> dirty) {
    Set<String> ids = new HashSet<String>();
    for (DocumentController.Buffer buffer : dirty) {
      ids.add(buffer.id());
      Long revision = buffer.snapshot().revision();
      if (renamed.remove(buffer.id())
          || (!revision.equals(written.get(buffer.id()))
              && !revision.equals(queued.get(buffer.id())))) {
        write(buffer);
      }
    }
    for (String id : new ArrayList<String>(tracked)) {
      if (!ids.contains(id)) {
        cleaned(id);
      }
    }
  }

  private void write(final DocumentController.Buffer buffer) {
    tracked.add(buffer.id());
    queued.put(buffer.id(), buffer.snapshot().revision());
    final RecoveryFile.Header header = header(buffer);
    final Path old = replaced.get(buffer.id());
    submit(
        new Runnable() {
          @Override
          public void run() {
            writeFile(header, buffer, old);
          }
        });
  }

  /** Records how a write ended; a failed one stays unwritten, so the next interval retries. */
  private void writeEnded(String id, Long revision, boolean ok) {
    if (revision.equals(queued.get(id))) {
      queued.remove(id);
    }
    if (ok && tracked.contains(id)) {
      written.put(id, revision);
      replaced.remove(id);
    }
  }

  private RecoveryFile.Header header(DocumentController.Buffer b) {
    String now = clock.instant().toString();
    String first = created.get(b.id());
    if (first == null) {
      first = now;
      created.put(b.id(), now);
    }
    FileBaseline base = b.base();
    return new RecoveryFile.Header(
        b.id(),
        b.path() == null ? "" : b.path().toString(),
        b.title() == null ? "" : b.title(),
        first,
        now,
        b.snapshot().revision(),
        b.snapshot().encoding().charset().name(),
        b.snapshot().encoding().bom(),
        b.snapshot().lineEnding().name(),
        base == null ? -1 : base.stamp().size(),
        base == null ? "" : base.stamp().modified().toInstant().toString(),
        base == null ? "" : base.digest(),
        b.language(),
        b.selections(),
        "");
  }

  private void cleaned(final String id) {
    created.remove(id);
    renamed.remove(id);
    written.remove(id);
    queued.remove(id);
    if (tracked.remove(id)) {
      final Path old = replaced.remove(id);
      submit(
          new Runnable() {
            @Override
            public void run() {
              deleteFile(id, old);
            }
          });
    }
  }

  // The queue.

  /** Reports whether no task is queued or running; for tests. */
  boolean idle() {
    return queue.isEmpty() && !runLock.isLocked();
  }

  private void submit(Runnable task) {
    queue.add(task);
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                drain();
              }
            });
    if (!started) {
      LOG.debug("Recovery task queued: the workers are closed");
    }
  }

  @WorkerThread
  private void drain() {
    runLock.lock();
    try {
      Runnable task = queue.poll();
      while (task != null) {
        task.run();
        task = queue.poll();
      }
    } finally {
      runLock.unlock();
    }
  }

  @WorkerThread
  private void openStore() {
    try {
      String instance =
          ProcessHandle.current().pid() + "-" + clock.millis() + "-" + OPENED.incrementAndGet();
      RecoveryStore opened = new RecoveryStore(settings, instance);
      opened.open();
      store = opened;
    } catch (IOException e) {
      LOG.warn("Recovery files are off: {}", LogText.oneLine(e.getMessage()));
    }
  }

  @WorkerThread
  private void claimDead(long oldest) {
    final List<Row> found = new ArrayList<Row>();
    try {
      if (store != null) {
        claims.addAll(store.claimDead(oldest));
      }
    } catch (IOException e) {
      LOG.warn("Cannot list recovery files: {}", LogText.oneLine(e.getMessage()));
    }
    for (RecoveryStore.Claim claim : claims) {
      for (RecoveryStore.Entry entry : claim.entries()) {
        found.add(new Row(entry));
      }
    }
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            offer(found);
          }
        });
  }

  /** Writes a buffer's file, then deletes the file it replaces; a failed write deletes nothing. */
  @WorkerThread
  private void writeFile(RecoveryFile.Header header, DocumentController.Buffer buffer, Path old) {
    final boolean ok = tryWrite(header, buffer);
    if (ok && old != null) {
      deleteRecovered(old);
    }
    final String id = buffer.id();
    final Long revision = buffer.snapshot().revision();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            writeEnded(id, revision, ok);
          }
        });
  }

  @WorkerThread
  private boolean tryWrite(RecoveryFile.Header header, DocumentController.Buffer buffer) {
    if (store == null) {
      return false;
    }
    try {
      store.write(header, buffer.snapshot().text());
      writeFailed = false;
      return true;
    } catch (IOException e) {
      LOG.warn("Cannot write a recovery file: {}", LogText.oneLine(e.getMessage()));
      if (!writeFailed) {
        writeFailed = true;
        ui.asyncExec(
            new Runnable() {
              @Override
              public void run() {
                notice(UiText.RECOVERY_WRITE_FAILED.text());
              }
            });
      }
      return false;
    }
  }

  @WorkerThread
  private void deleteFile(String id, Path old) {
    if (old != null) {
      deleteRecovered(old);
    }
    if (store != null) {
      try {
        store.delete(id);
      } catch (IOException e) {
        LOG.warn("Cannot delete a recovery file: {}", LogText.oneLine(e.getMessage()));
      }
    }
  }

  // The dialog.

  private void offer(List<Row> found) {
    ApplicationController window = set.target();
    if (!closing && window != null && !found.isEmpty()) {
      rows.addAll(found);
      dialog = window.view();
      dialog.showRecovery(this);
    } else {
      offerEnded();
    }
  }

  /**
   * Runs a task on the UI thread once {@link #start}'s offer has ended: no buffer was found, or the
   * dialog's rows are all handled, or Later was pressed. Runs it now when the offer has ended.
   *
   * @param task the task
   */
  void afterOffer(Runnable task) {
    if (offerEnded) {
      task.run();
    } else {
      afterOffer.add(task);
    }
  }

  private void offerEnded() {
    if (!offerEnded) {
      offerEnded = true;
      List<Runnable> tasks = new ArrayList<Runnable>(afterOffer);
      afterOffer.clear();
      for (Runnable task : tasks) {
        task.run();
      }
    }
  }

  @Override
  public List<RecoveryRow> rows() {
    List<RecoveryRow> list = new ArrayList<RecoveryRow>();
    for (Row row : rows) {
      list.add(row(row.entry));
    }
    return list;
  }

  private static RecoveryRow row(RecoveryStore.Entry entry) {
    RecoveryFile.Header h = entry.header();
    String name;
    if (h == null) {
      name = String.valueOf(entry.file().getFileName());
    } else {
      name = h.path().isEmpty() ? h.title() : h.path();
    }
    String state =
        entry.unreadable()
            ? UiText.RECOVERY_FILE_UNREADABLE.text()
            : entry.damaged()
                ? UiText.RECOVERY_DAMAGED.text()
                : switch (entry.original()) {
                  case NONE -> UiText.RECOVERY_NO_FILE.text();
                  case UNCHANGED -> UiText.RECOVERY_UNCHANGED.text();
                  case CHANGED -> UiText.RECOVERY_CHANGED.text();
                  case MISSING -> UiText.RECOVERY_MISSING.text();
                  case UNREADABLE -> UiText.RECOVERY_UNREADABLE.text();
                };
    return new RecoveryRow(name, time(h), state, entry.damaged());
  }

  private static String time(RecoveryFile.Header header) {
    if (header == null) {
      return "";
    }
    try {
      return TIME.format(Instant.parse(header.written()).truncatedTo(ChronoUnit.SECONDS));
    } catch (DateTimeParseException e) {
      return header.written();
    }
  }

  @Override
  public void restore(int[] indexes) {
    for (int index : indexes) {
      if (index >= 0 && index < rows.size()) {
        final Row row = rows.get(index);
        if (row.entry.damaged()) {
          notice(UiText.RECOVERY_DAMAGED_KEPT.text());
        } else if (!row.reading) {
          row.reading = true;
          submit(
              new Runnable() {
                @Override
                public void run() {
                  read(row);
                }
              });
        }
      }
    }
  }

  @WorkerThread
  private void read(final Row row) {
    Result result = load(row.entry.file());
    final DocumentController.Recovered recovered = result.recovered();
    final String problem = result.problem();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            read(row, recovered, problem);
          }
        });
  }

  private void read(Row row, DocumentController.Recovered recovered, String problem) {
    row.reading = false;
    if (recovered == null) {
      notice(problem);
      dialog.recoveryChanged();
      return;
    }
    ApplicationController window = set.target();
    DocumentController.Buffer buffer =
        closing || window == null
            ? null
            : receiver(recovered, window.documents()).addRecovered(recovered, null);
    if (buffer == null) {
      budget.release(recovered.reserved());
      notice(UiText.RECOVERY_TAB_DIRTY.format(row(row.entry).name()));
      return;
    }
    adopt(buffer, row.entry.file());
    rows.remove(row);
    rowsChanged();
  }

  /** Returns the window holding the buffer's file, else {@code preferred}. */
  private DocumentController receiver(
      DocumentController.Recovered recovered, DocumentController preferred) {
    DocumentController holder = recovered.real() == null ? null : set.holderAt(recovered.real());
    return holder == null ? preferred : holder;
  }

  /** Writes a recovered buffer in this instance's directory; its old file goes after that. */
  private void adopt(DocumentController.Buffer buffer, Path old) {
    replaced.put(buffer.id(), old);
    write(buffer);
  }

  @WorkerThread
  private void deleteRecovered(Path file) {
    try {
      RecoveryStore.deleteFile(file);
    } catch (IOException e) {
      LOG.warn("Cannot delete a recovery file: {}", LogText.oneLine(e.getMessage()));
    }
  }

  @Override
  public void discard(int[] indexes) {
    List<Row> chosen = new ArrayList<Row>();
    for (int index : indexes) {
      if (index >= 0 && index < rows.size() && !rows.get(index).reading) {
        chosen.add(rows.get(index));
      }
    }
    for (final Row row : chosen) {
      rows.remove(row);
      submit(
          new Runnable() {
            @Override
            public void run() {
              deleteRecovered(row.entry.file());
            }
          });
    }
    rowsChanged();
  }

  @Override
  public void later() {
    rows.clear();
    releaseClaims();
    offerEnded();
  }

  private void rowsChanged() {
    dialog.recoveryChanged();
    if (rows.isEmpty()) {
      releaseClaims();
      offerEnded();
    }
  }

  private void releaseClaims() {
    submit(
        new Runnable() {
          @Override
          public void run() {
            closeClaims();
          }
        });
  }

  @WorkerThread
  private void closeClaims() {
    for (RecoveryStore.Claim claim : claims) {
      claim.close();
    }
    claims.clear();
  }

  // Reading a buffer.

  /** A buffer read on a worker, or why it was not. */
  private record Result(DocumentController.Recovered recovered, String problem) {}

  /** Reserves the buffer's heap and reads its file; nothing stays reserved on a failure. */
  @WorkerThread
  private Result load(Path file) {
    LoadReservation reservation = null;
    Path name = file.getFileName();
    try {
      LoadCost cost = DocumentFiles.estimate(file, options.limits());
      reservation = LoadReservation.reserve(budget, cost);
      if (reservation == null) {
        return new Result(null, UiText.RECOVERY_TOO_LARGE.format(name));
      }
      RecoveryFile.Loaded loaded = RecoveryFile.load(file, reservation);
      RecoveryFile.Header header = loaded.header();
      Document document = loaded.text().build(encoding(header), options.empty(), options.limits());
      DisplayProjection.Layout layout =
          DisplayProjection.layout(document.text(), 0, new CancellationToken());
      Path given = header.path().isEmpty() ? null : Path.of(header.path());
      Path real = given == null ? null : SaveAndReload.realFile(given);
      FileBaseline base = given == null ? null : baseline(header, real);
      long reserved = reservation.keep(loaded.text().retainedBytes(options.limits()));
      return new Result(
          new DocumentController.Recovered(header, document, layout, real, given, base, reserved),
          null);
    } catch (RecoveryFile.DamagedException e) {
      return new Result(null, UiText.RECOVERY_DAMAGED_KEPT.text());
    } catch (LoadTooLargeException e) {
      return new Result(null, UiText.RECOVERY_TOO_LARGE.format(name));
    } catch (IOException | InvalidPathException e) {
      LOG.warn("Cannot read a recovery file: {}", LogText.oneLine(e.getMessage()));
      return new Result(null, UiText.RECOVERY_READ_FAILED.format(name));
    } finally {
      if (reservation != null) {
        reservation.close();
      }
    }
  }

  private static TextEncoding encoding(RecoveryFile.Header header) {
    Charset charset = SessionText.charset(header.encoding());
    if (charset == null) {
      LOG.warn(
          "The recovered encoding {} is not available; using UTF-8",
          LogText.oneLine(header.encoding()));
      return TextEncoding.of(StandardCharsets.UTF_8, false);
    }
    try {
      return TextEncoding.of(charset, header.bom());
    } catch (IllegalArgumentException e) {
      return TextEncoding.of(charset, false);
    }
  }

  /**
   * Returns the baseline a recovered buffer starts from: the file as it stands when it still holds
   * the buffer's base, else the recorded base, which shows the change and makes a save ask. A file
   * the buffer never matched gets a stamp that matches no file; so does a file that cannot be read.
   */
  @WorkerThread
  private static FileBaseline baseline(RecoveryFile.Header header, Path real) {
    try {
      FileBaseline now = FileCheck.read(real, new CancellationToken());
      if (now == null || now.digest().equals(header.baseSha256())) {
        return now;
      }
    } catch (IOException e) {
      LOG.warn("Cannot read a recovered buffer's file: {}", LogText.oneLine(e.getMessage()));
    }
    FileTime modified = FileTime.fromMillis(0);
    try {
      modified = FileTime.from(Instant.parse(header.baseModified()));
    } catch (DateTimeParseException e) {
      LOG.debug("A recovery file names no base time");
    }
    return new FileBaseline(
        new FileStamp(header.baseSize(), modified, null),
        header.baseSha256(),
        System.currentTimeMillis());
  }

  // Snapshots.

  /**
   * Writes the buffers as the snapshot of a quit and deletes older snapshots.
   *
   * @param buffers the dirty buffers
   * @param done receives on the UI thread whether every file was written
   */
  void writeSnapshot(final List<DocumentController.Buffer> buffers, final Consumer<Boolean> done) {
    final List<RecoveryFile.Header> headers = new ArrayList<RecoveryFile.Header>();
    for (DocumentController.Buffer buffer : buffers) {
      headers.add(header(buffer));
    }
    submit(
        new Runnable() {
          @Override
          public void run() {
            final boolean ok = writeSnapshots(headers, buffers);
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    done.accept(ok);
                  }
                });
          }
        });
  }

  @WorkerThread
  private boolean writeSnapshots(
      List<RecoveryFile.Header> headers, List<DocumentController.Buffer> buffers) {
    if (store == null) {
      return false;
    }
    try {
      Set<String> ids = new HashSet<String>();
      for (int i = 0; i < buffers.size(); i++) {
        store.writeSnapshot(headers.get(i), buffers.get(i).snapshot().text());
        ids.add(buffers.get(i).id());
      }
      store.pruneSnapshots(ids);
      return true;
    } catch (IOException e) {
      LOG.warn("Cannot write the snapshot: {}", LogText.oneLine(e.getMessage()));
      return false;
    }
  }

  /**
   * Opens a session tab's snapshot buffer dirty; its file goes once the buffer's recovery file is
   * written.
   *
   * @param tab the tab
   * @param documents the window the tab belongs to
   * @param failed runs on the UI thread with a message when the snapshot is missing or damaged,
   *     which is kept
   * @param done runs on the UI thread at the end
   */
  void restoreSnapshot(
      final Session.Tab tab,
      final DocumentController documents,
      final Consumer<String> failed,
      final Runnable done) {
    submit(
        new Runnable() {
          @Override
          public void run() {
            readSnapshot(tab, documents, failed, done);
          }
        });
  }

  @WorkerThread
  private void readSnapshot(
      final Session.Tab tab,
      final DocumentController documents,
      final Consumer<String> failed,
      final Runnable done) {
    Result result = new Result(null, UiText.RECOVERY_READ_FAILED.format(tab.buffer()));
    Path found = null;
    try {
      found = RecoveryStore.snapshotFile(settings, tab.buffer());
      result = load(found);
    } catch (IOException e) {
      LOG.debug("Cannot find a snapshot: {}", LogText.oneLine(e.getMessage()));
    } finally {
      final Path file = found;
      final Result read = result;
      ui.asyncExec(
          new Runnable() {
            @Override
            public void run() {
              snapshotRead(tab, documents, file, read, failed, done);
            }
          });
    }
  }

  private void snapshotRead(
      Session.Tab tab,
      DocumentController documents,
      Path file,
      Result read,
      Consumer<String> failed,
      Runnable done) {
    try {
      if (read.recovered() == null) {
        failed.accept(read.problem());
        return;
      }
      DocumentController.Buffer buffer =
          receiver(read.recovered(), documents).addRecovered(read.recovered(), tab);
      if (buffer == null) {
        budget.release(read.recovered().reserved());
      } else {
        adopt(buffer, file);
      }
    } finally {
      done.run();
    }
  }

  // Quit.

  /**
   * Stops the interval and queues the last writes.
   *
   * @param unsaved the buffers to keep for the next start; the directory goes when there are none
   */
  void quitting(List<DocumentController.Buffer> unsaved) {
    closing = true;
    ui.cancelTimer(timer);
    keep = !unsaved.isEmpty();
    write(unsaved);
  }

  /** Runs the queue, closes the claims and releases the instance's lock. Blocking. */
  @WorkerThread
  void finish() {
    drain();
    runLock.lock();
    try {
      closeClaims();
      if (store != null) {
        store.close(keep);
      }
    } finally {
      runLock.unlock();
    }
  }
}
