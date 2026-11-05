package io.github.ajdailey.qcaret.launcher;

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
 * created owner-only. A {@link LogFileHandler} on the root logger writes each line on the logging
 * thread; no lock file is written. A failed write or open detaches the file and logs an ERROR,
 * which Show Log lists; a failed roll logs a WARN and the file keeps growing.
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

  /** Age past which {@link #prune} deletes a {@code qcaret-<pid>.log} file. */
  static final Duration PRUNE_AGE = Duration.ofDays(7);

  private static final Logger LOG = LoggerFactory.getLogger(FileLog.class);
  private static final Pattern PID_FILE = Pattern.compile("qcaret-([0-9]{1,18})\\.log(\\.1)?");

  private final Path directory;
  private final String fileName;
  private final long rollBytes;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile LogFileHandler handler;

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
    this(settings, file, ROLL_BYTES);
  }

  /**
   * Creates the log file handle with a roll-over size; nothing is opened.
   *
   * @param settings the settings directory
   * @param file the file's name in the folder
   * @param rollBytes size at which the file rolls over
   * @throws IOException when the names cannot be resolved inside the settings directory
   */
  FileLog(Path settings, String file, long rollBytes) throws IOException {
    this.directory = SafePaths.child(settings, FOLDER);
    this.fileName = file;
    this.rollBytes = rollBytes;
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
    return handler != null;
  }

  @Override
  public void attach() throws IOException {
    lock.lock();
    try {
      if (handler != null) {
        return;
      }
      Path dir = SafePaths.settingsDirectory(directory);
      LogFileHandler opened =
          new LogFileHandler(
              SafePaths.child(dir, fileName),
              rollBytes,
              new LogFileHandler.Failure() {
                @Override
                public void failed(LogFileHandler failed, IOException error) {
                  writeFailed(failed, error);
                }

                @Override
                public void rollFailed(LogFileHandler failed, IOException error) {
                  LOG.warn(
                      "Debug log file not rolled over; it keeps growing: {}",
                      LogText.oneLine(
                          error.getClass().getSimpleName() + ": " + error.getMessage()));
                }
              });
      handler = opened;
      LogConfigurator.setFile(opened, true);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void detach() {
    lock.lock();
    try {
      LogFileHandler current = handler;
      if (current != null) {
        stop(current);
      }
    } finally {
      lock.unlock();
    }
  }

  /** Removes and closes the handler; holds the lock. */
  private void stop(LogFileHandler current) {
    handler = null;
    LogConfigurator.setFile(current, false);
    current.close();
  }

  /** Detaches a handler whose write failed, unless a detach or a new attach replaced it. */
  private void writeFailed(LogFileHandler failed, IOException error) {
    lock.lock();
    try {
      if (handler != failed) {
        return;
      }
      stop(failed);
    } finally {
      lock.unlock();
    }
    LOG.error(
        "Debug log file closed after a write failure: {}",
        LogText.oneLine(error.getClass().getSimpleName() + ": " + error.getMessage()));
  }
}
