package io.github.ajdailey.qcaret.launcher;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

/**
 * Appends each formatted record to a file in UTF-8 with one unbuffered write, on the logging
 * thread. Before a write would take the file past {@code rollBytes}, it moves the file to {@code
 * <name>.1}, replacing it, and starts a new one. A file it creates is {@code rw-------} on POSIX.
 * It writes nothing beside the file and its {@code .1}.
 *
 * <p>A failed move reopens the file and keeps appending past {@code rollBytes}; the next record
 * over the size tries the move again. The first failed move after a successful one runs {@link
 * Failure#rollFailed}. The first failed write, close or open closes the file and runs {@link
 * Failure#failed}; later records are dropped. Both callbacks run after the handler's lock is
 * released.
 */
final class LogFileHandler extends Handler {
  /** Receives the handler's failures. */
  interface Failure {
    /**
     * Called once, on the logging thread, outside the handler's lock, when a write, close or open
     * fails and the file is closed.
     *
     * @param handler the failed handler
     * @param error the cause
     */
    void failed(LogFileHandler handler, IOException error);

    /**
     * Called on the logging thread, outside the handler's lock, when a move to {@code .1} fails and
     * the last move did not; the handler keeps writing the file.
     *
     * @param handler the handler
     * @param error the cause
     */
    void rollFailed(LogFileHandler handler, IOException error);
  }

  private final Path file;
  private final Path rolled;
  private final long rollBytes;
  private final Failure failure;
  private OutputStream out;
  private long size;
  private boolean rollFailing;

  /**
   * Opens the file for append, creating it when missing.
   *
   * @param file the log file
   * @param rollBytes size past which the file rolls over
   * @param failure receives the first write failure
   * @throws IOException when the file cannot be opened
   */
  LogFileHandler(Path file, long rollBytes, Failure failure) throws IOException {
    this.file = file;
    this.rolled = file.resolveSibling(file.getFileName() + ".1");
    this.rollBytes = rollBytes;
    this.failure = failure;
    open();
  }

  private void open() throws IOException {
    if (!Files.exists(file)
        && file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    out =
        Files.newOutputStream(
            file, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    size = Files.size(file);
  }

  @Override
  public void publish(LogRecord record) {
    if (!isLoggable(record)) {
      return;
    }
    byte[] bytes = getFormatter().format(record).getBytes(StandardCharsets.UTF_8);
    IOException error = null;
    IOException rollError = null;
    synchronized (this) {
      if (out == null) {
        return;
      }
      try {
        if (size > 0 && size + bytes.length > rollBytes) {
          rollError = roll();
        }
        out.write(bytes);
        size += bytes.length;
      } catch (IOException e) {
        closeQuietly();
        error = e;
      }
    }
    if (error != null) {
      failure.failed(this, error);
    } else if (rollError != null) {
      failure.rollFailed(this, rollError);
    }
  }

  /**
   * Closes the file, moves it to {@code .1} and opens a new one, or reopens the same file when the
   * move fails; holds the lock.
   *
   * @return the move's failure when the last move succeeded, else null
   * @throws IOException when the close or the open fails
   */
  private IOException roll() throws IOException {
    OutputStream current = out;
    out = null;
    current.close();
    IOException moveError = null;
    try {
      Files.move(file, rolled, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      moveError = e;
    }
    open();
    boolean report = moveError != null && !rollFailing;
    rollFailing = moveError != null;
    return report ? moveError : null;
  }

  private void closeQuietly() {
    OutputStream current = out;
    out = null;
    if (current != null) {
      try {
        current.close();
      } catch (IOException e) {
        // The file is already failed; its first error is reported.
      }
    }
  }

  @Override
  public void flush() {
    // Each record is written unbuffered.
  }

  @Override
  public synchronized void close() {
    closeQuietly();
  }
}
