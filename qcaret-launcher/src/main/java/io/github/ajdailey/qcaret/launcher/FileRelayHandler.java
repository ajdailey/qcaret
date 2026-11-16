package io.github.ajdailey.qcaret.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * The root logger's handler for the debug log file: it hands each record to the attached {@link
 * LogFileHandler}, or drops it while none is attached. Between {@link #hold} and the first attach
 * or {@link #release} it keeps each record formatted instead, up to {@link #HELD_CHARS} characters;
 * a record past the bound is counted and dropped. An attach writes the kept records to the file in
 * order, then one WARN naming the dropped count when there is one; {@link #release} discards them.
 * A record is kept or handed on, never both, and the kept records are written under this handler's
 * lock, so each reaches the file once.
 */
final class FileRelayHandler extends Handler {
  /** Characters of formatted records kept during a hold. */
  static final int HELD_CHARS = 1 << 20;

  private final List<String> held = new ArrayList<String>();
  private int heldChars;
  private int dropped;
  private boolean holding;
  private LogFileHandler target;

  /** Keeps every later record until {@link #setTarget} attaches a file or {@link #release}. */
  synchronized void hold() {
    holding = true;
  }

  /** Ends a hold and discards the kept records. */
  synchronized void release() {
    holding = false;
    held.clear();
    heldChars = 0;
    dropped = 0;
  }

  /**
   * Sets the file every later record goes to. During a hold, a file first receives the kept records
   * and the hold ends; null keeps a hold in effect.
   *
   * @param file the file's handler, or null to drop records
   */
  synchronized void setTarget(LogFileHandler file) {
    target = file;
    if (file == null || !holding) {
      return;
    }
    List<String> kept = new ArrayList<String>(held);
    int lost = dropped;
    release();
    for (String text : kept) {
      file.write(text);
    }
    if (lost > 0) {
      LogRecord record =
          new LogRecord(
              Level.WARNING,
              lost + " startup log records past " + HELD_CHARS + " characters not kept");
      record.setLoggerName(LogConfigurator.class.getName());
      file.write(getFormatter().format(record));
    }
  }

  /**
   * Returns the file records go to.
   *
   * @return the handler, or null
   */
  synchronized LogFileHandler target() {
    return target;
  }

  @Override
  public void publish(LogRecord record) {
    if (!isLoggable(record)) {
      return;
    }
    LogFileHandler file;
    synchronized (this) {
      if (holding) {
        String text = getFormatter().format(record);
        if (heldChars + text.length() > HELD_CHARS) {
          dropped++;
        } else {
          held.add(text);
          heldChars += text.length();
        }
        return;
      }
      file = target;
    }
    if (file != null) {
      file.publish(record);
    }
  }

  @Override
  public void flush() {
    // Nothing is buffered past a hold.
  }

  @Override
  public void close() {
    // The file's handler is closed through FileLog.
  }
}
