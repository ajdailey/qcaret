package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.log.RecentLog;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** Appends each formatted record to a {@link RecentLog}, WARN and above as warnings. */
final class RecentHandler extends Handler {
  private final RecentLog recent;

  RecentHandler(RecentLog recent) {
    this.recent = recent;
  }

  @Override
  public void publish(LogRecord record) {
    if (isLoggable(record)) {
      recent.append(
          getFormatter().format(record), record.getLevel().intValue() >= Level.WARNING.intValue());
    }
  }

  @Override
  public void flush() {
    // Nothing is buffered.
  }

  @Override
  public void close() {
    // The lines stay for Show Log.
  }
}
