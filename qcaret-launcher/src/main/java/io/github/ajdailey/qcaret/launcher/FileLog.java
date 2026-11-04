package io.github.ajdailey.qcaret.launcher;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.recovery.ResilientOutputStreamBase;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.util.FileSize;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The debug log file at DEBUG, rolled to {@code <name>.1} at {@link #ROLL_BYTES}: {@code
 * <settings>/logs/qcaret.log} for the instance holding the handoff lock, {@code qcaret-<pid>.log}
 * for any other process ({@link #fileName}), so no two processes roll one file. The folder is
 * created owner-only. A logback {@code AsyncAppender} named {@code qcaret-logfile} writes from a
 * {@link #QUEUE_LINES}-line queue on its own daemon thread ({@code
 * AsyncAppender-Worker-qcaret-logfile}), so a slow disk never holds the thread that logs; a line
 * the full queue has no room for is dropped. An error status from the file appender, its rolling
 * policy or its output stream detaches the file and logs an ERROR, which Show Log lists.
 *
 * <p>{@link #prune} deletes other processes' old {@code qcaret-<pid>.log} files; the primary runs
 * it at startup with the file attached.
 */
final class FileLog implements LogFiles {
  /** The log file's name. */
  static final String FILE = "qcaret.log";

  /** The folder's name in the settings directory. */
  static final String FOLDER = "logs";

  /** Size at which the file rolls over. */
  static final long ROLL_BYTES = 10L << 20;

  /** Lines the writer queue holds. */
  static final int QUEUE_LINES = 4096;

  /** Age past which {@link #prune} deletes a {@code qcaret-<pid>.log} file. */
  static final Duration PRUNE_AGE = Duration.ofDays(7);

  private static final Logger LOG = LoggerFactory.getLogger(FileLog.class);
  private static final Pattern PID_FILE = Pattern.compile("qcaret-([0-9]{1,18})\\.log(\\.1)?");

  private final Path directory;
  private final String fileName;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile AsyncAppender writer;
  private volatile RollingFileAppender<ILoggingEvent> file;
  private StatusListener failures;
  private volatile boolean attached;

  /**
   * Creates the log file handle; nothing is opened.
   *
   * @param settings the settings directory
   * @throws IOException when the folder name cannot be resolved inside it
   */
  FileLog(Path settings) throws IOException {
    this(settings, FILE);
  }

  /**
   * Creates the log file handle for a named file; nothing is opened.
   *
   * @param settings the settings directory
   * @param file the file's name in the folder, from {@link #fileName}
   * @throws IOException when the names cannot be resolved inside the settings directory
   */
  FileLog(Path settings, String file) throws IOException {
    this.directory = SafePaths.child(settings, FOLDER);
    this.fileName = file;
    SafePaths.child(directory, file);
  }

  /**
   * Returns the log file's name for a process.
   *
   * @param primary true for the instance holding the handoff lock
   * @param pid the process id
   * @return {@link #FILE}, or {@code qcaret-<pid>.log}
   */
  static String fileName(boolean primary, long pid) {
    return primary ? FILE : "qcaret-" + pid + ".log";
  }

  /**
   * Deletes each regular file in the folder named {@code qcaret-<pid>.log} or its rolled {@code
   * qcaret-<pid>.log.1} that was last modified more than {@link #PRUNE_AGE} before {@code now} and
   * whose process is not running. A file this or another live process writes is kept: its process
   * is running, and its writes keep it young. Nothing else in the folder is touched. Blocks; call
   * on a worker.
   *
   * @param now the current time
   * @param running reports whether a process id names a running process
   * @return the number of files deleted
   */
  int prune(Instant now, LongPredicate running) {
    Instant cutoff = now.minus(PRUNE_AGE);
    int deleted = 0;
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
      for (Path entry : entries) {
        Path name = entry.getFileName();
        Matcher matcher = PID_FILE.matcher(name == null ? "" : name.toString());
        if (!matcher.matches() || running.test(Long.parseLong(matcher.group(1)))) {
          continue;
        }
        if (deleteIfOlder(SafePaths.child(directory, name.toString()), cutoff)) {
          deleted++;
        }
      }
    } catch (NoSuchFileException e) {
      return 0;
    } catch (IOException e) {
      LOG.warn("Cannot list {}: {}", LogText.oneLine(directory), LogText.oneLine(e.getMessage()));
    }
    if (deleted > 0) {
      LOG.info("Deleted {} per-process log files older than {} days", deleted, PRUNE_AGE.toDays());
    }
    return deleted;
  }

  private static boolean deleteIfOlder(Path file, Instant cutoff) {
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isRegularFile()
          || !attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
        return false;
      }
      return Files.deleteIfExists(file);
    } catch (IOException e) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Cannot delete {}: {}", LogText.oneLine(file), LogText.oneLine(e.getMessage()));
      }
      return false;
    }
  }

  /**
   * Reports whether a process id names a running process.
   *
   * @param pid a process id
   * @return true while it runs
   */
  static boolean running(long pid) {
    return ProcessHandle.of(pid).isPresent();
  }

  @Override
  public Path directory() {
    return directory;
  }

  @Override
  public boolean attached() {
    return attached;
  }

  @Override
  public void attach() throws IOException {
    lock.lock();
    try {
      if (attached) {
        return;
      }
      stopWriter();
      Path dir = SafePaths.settingsDirectory(directory);
      Path target = SafePaths.child(dir, fileName);
      if (!Files.exists(target)
          && dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(
            target,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      }
      LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
      PatternLayoutEncoder encoder = new PatternLayoutEncoder();
      encoder.setContext(context);
      encoder.setPattern(LogbackConfigurator.PATTERN);
      encoder.start();
      RollingFileAppender<ILoggingEvent> appender = new RollingFileAppender<ILoggingEvent>();
      appender.setContext(context);
      appender.setName("qcaret-file");
      appender.setFile(target.toString());
      appender.setAppend(true);
      appender.setEncoder(encoder);
      FixedWindowRollingPolicy rolling = new FixedWindowRollingPolicy();
      rolling.setContext(context);
      rolling.setParent(appender);
      rolling.setFileNamePattern(target + ".%i");
      rolling.setMinIndex(1);
      rolling.setMaxIndex(1);
      rolling.start();
      SizeBasedTriggeringPolicy<ILoggingEvent> trigger =
          new SizeBasedTriggeringPolicy<ILoggingEvent>();
      trigger.setContext(context);
      trigger.setMaxFileSize(new FileSize(ROLL_BYTES));
      trigger.start();
      appender.setRollingPolicy(rolling);
      appender.setTriggeringPolicy(trigger);
      appender.start();
      if (!appender.isStarted()) {
        appender.stop();
        throw new IOException("Cannot open " + target);
      }
      AsyncAppender async = new AsyncAppender();
      async.setContext(context);
      async.setName("qcaret-logfile");
      async.setQueueSize(QUEUE_LINES);
      async.setDiscardingThreshold(0);
      async.setNeverBlock(true);
      async.addAppender(appender);
      async.start();
      file = appender;
      writer = async;
      watchFailures(context, appender, rolling);
      root(context).addAppender(async);
      attached = true;
      LogbackConfigurator.setFileAttached(true);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void detach() {
    lock.lock();
    try {
      stopWriter();
    } finally {
      lock.unlock();
    }
  }

  /** Detaches and stops the writer, which flushes the queue for up to a second; holds the lock. */
  private void stopWriter() {
    AsyncAppender async = writer;
    writer = null;
    file = null;
    if (attached) {
      attached = false;
      LogbackConfigurator.setFileAttached(false);
    }
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    if (failures != null) {
      context.getStatusManager().remove(failures);
      failures = null;
    }
    if (async != null) {
      root(context).detachAppender(async);
      async.stop();
    }
  }

  /**
   * Detaches the file at its first error status. Runs on the thread that hit the error, usually the
   * writer itself, so it only detaches; the next {@link #attach} or {@link #detach} stops it.
   */
  private void watchFailures(
      LoggerContext context,
      final RollingFileAppender<ILoggingEvent> appender,
      final FixedWindowRollingPolicy rolling) {
    failures =
        new StatusListener() {
          @Override
          public void addStatusEvent(Status status) {
            Object origin = status.getOrigin();
            boolean ours =
                origin == appender
                    || origin == rolling
                    || origin instanceof ResilientOutputStreamBase;
            if (status.getLevel() == Status.ERROR && ours && file == appender) {
              failed(appender, status.getMessage());
            }
          }
        };
    context.getStatusManager().add(failures);
  }

  private void failed(RollingFileAppender<ILoggingEvent> appender, String message) {
    AsyncAppender async = writer;
    if (!attached || async == null || file != appender) {
      return;
    }
    attached = false;
    LogbackConfigurator.setFileAttached(false);
    root((LoggerContext) LoggerFactory.getILoggerFactory()).detachAppender(async);
    LOG.error("Debug log file closed after a write failure: {}", LogText.oneLine(message));
  }

  private static ch.qos.logback.classic.Logger root(LoggerContext context) {
    return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }
}
