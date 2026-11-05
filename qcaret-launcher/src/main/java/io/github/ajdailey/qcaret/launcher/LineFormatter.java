package io.github.ajdailey.qcaret.launcher;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Formats a record as one line, {@code 2026-11-04 12:00:00,123 WARN [thread] i.g.a.q.l.Launcher -
 * message}, then the stack trace when the record carries a throwable. Levels print as SLF4J names
 * ({@link #levelName}); the logger name is shortened to {@link #LOGGER_CHARS} ({@link
 * #abbreviate}). CR, LF, NEL, U+2028 and U+2029 in the message become {@code _}, behind the call
 * sites' own {@code LogText.oneLine}.
 *
 * <p>The thread is the current thread, so every handler that uses this formatter publishes on the
 * logging thread.
 */
final class LineFormatter extends Formatter {
  /** Longest logger name before its packages are cut to one letter. */
  static final int LOGGER_CHARS = 20;

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS");

  @Override
  public String format(LogRecord record) {
    StringBuilder line = new StringBuilder(160);
    line.append(TIME.format(record.getInstant().atZone(ZoneId.systemDefault()))).append(' ');
    String level = levelName(record.getLevel());
    line.append(level);
    for (int i = level.length(); i < 5; i++) {
      line.append(' ');
    }
    line.append(" [").append(Thread.currentThread().getName()).append("] ");
    line.append(abbreviate(String.valueOf(record.getLoggerName()), LOGGER_CHARS)).append(" - ");
    String message = formatMessage(record);
    String text = message == null ? "null" : message;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      boolean lineBreak = c == '\r' || c == '\n' || c == 0x85 || c == 0x2028 || c == 0x2029;
      line.append(lineBreak ? '_' : c);
    }
    line.append(System.lineSeparator());
    Throwable thrown = record.getThrown();
    if (thrown != null) {
      StringWriter trace = new StringWriter();
      try (PrintWriter out = new PrintWriter(trace)) {
        thrown.printStackTrace(out);
      }
      line.append(trace);
    }
    return line.toString();
  }

  /**
   * Returns the SLF4J name for a JUL level: SEVERE ERROR, WARNING WARN, INFO and CONFIG INFO, FINE
   * DEBUG, FINER and below TRACE.
   *
   * @param level the record's level
   * @return the name
   */
  static String levelName(Level level) {
    int value = level.intValue();
    if (value >= Level.SEVERE.intValue()) {
      return "ERROR";
    }
    if (value >= Level.WARNING.intValue()) {
      return "WARN";
    }
    if (value >= Level.CONFIG.intValue()) {
      return "INFO";
    }
    if (value >= Level.FINE.intValue()) {
      return "DEBUG";
    }
    return "TRACE";
  }

  /**
   * Shortens a dotted name to {@code target} chars: package segments, leftmost first, become their
   * first letter until the name fits or only the last segment is whole.
   *
   * @param name a logger name
   * @param target the length to reach
   * @return the name, or its shortened form
   */
  static String abbreviate(String name, int target) {
    int excess = name.length() - target;
    if (excess <= 0) {
      return name;
    }
    StringBuilder out = new StringBuilder(name.length());
    int start = 0;
    int dot = name.indexOf('.');
    while (dot >= 0) {
      int length = dot - start;
      if (excess > 0 && length > 1) {
        out.append(name.charAt(start));
        excess -= length - 1;
      } else {
        out.append(name, start, dot);
      }
      out.append('.');
      start = dot + 1;
      dot = name.indexOf('.', start);
    }
    return out.append(name, start, name.length()).toString();
  }
}
