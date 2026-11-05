package io.github.ajdailey.qcaret.launcher;

import java.util.logging.Handler;
import java.util.logging.LogRecord;

/** Prints each formatted record to the current {@code System.err} and flushes it. */
final class StderrHandler extends Handler {
  @Override
  public void publish(LogRecord record) {
    if (isLoggable(record)) {
      String text = getFormatter().format(record);
      synchronized (this) {
        System.err.print(text);
        System.err.flush();
      }
    }
  }

  @Override
  public void flush() {
    System.err.flush();
  }

  @Override
  public void close() {
    // System.err stays open.
  }
}
