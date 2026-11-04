package io.github.ajdailey.qcaret.app.log;

import io.github.ajdailey.qcaret.core.util.Checks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The last log lines, for Show Log. Holds at most {@code capacity} lines, dropping the oldest; a
 * line longer than {@code maxChars} is cut and ends with {@code ...}. Safe for any thread: append
 * and snapshot each hold the lock only to copy references.
 *
 * <p>Lines at WARN and above count as warnings; the warning listener runs on the appending thread
 * after each one, outside the lock.
 */
public final class RecentLog {
  /** Lines the application keeps. */
  public static final int LINES = 500;

  /** Longest line the application keeps, in chars. */
  public static final int LINE_MAX_CHARS = 2000;

  private final int capacity;
  private final int maxChars;
  private final ArrayDeque<String> lines;
  private final AtomicLong warnings = new AtomicLong();
  private volatile Runnable warningListener;

  /**
   * Creates an empty log.
   *
   * @param capacity lines kept, at least 1
   * @param maxChars longest line kept, at least 1
   */
  public RecentLog(int capacity, int maxChars) {
    Checks.atLeast(capacity, 1, "capacity");
    Checks.atLeast(maxChars, 1, "maxChars");
    this.capacity = capacity;
    this.maxChars = maxChars;
    this.lines = new ArrayDeque<String>(capacity);
  }

  /**
   * Appends one line, trailing whitespace removed.
   *
   * @param line the formatted line
   */
  public void append(String line) {
    append(line, false);
  }

  /**
   * Appends one line, trailing whitespace removed.
   *
   * @param line the formatted line
   * @param warning true for a WARN or ERROR line
   */
  public void append(String line, boolean warning) {
    String text = line.stripTrailing();
    if (text.length() > maxChars) {
      text = text.substring(0, maxChars) + "...";
    }
    synchronized (lines) {
      if (lines.size() == capacity) {
        lines.removeFirst();
      }
      lines.addLast(text);
    }
    if (warning) {
      warnings.incrementAndGet();
      Runnable listener = warningListener;
      if (listener != null) {
        listener.run();
      }
    }
  }

  /**
   * Returns how many warnings were appended.
   *
   * @return the count since creation
   */
  public long warnings() {
    return warnings.get();
  }

  /**
   * Sets the code to run after each warning line.
   *
   * @param listener runs on the logging thread; must not block; null removes it
   */
  public void setWarningListener(Runnable listener) {
    warningListener = listener;
  }

  /**
   * Returns the lines, oldest first.
   *
   * @return a copy
   */
  public List<String> snapshot() {
    synchronized (lines) {
      return new ArrayList<String>(lines);
    }
  }
}
