package io.github.ajdailey.qcaret.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.ajdailey.qcaret.app.log.RecentLog;

/**
 * Formats each event with the encoder pattern and appends it to a {@link RecentLog}, WARN and above
 * as warnings.
 */
final class RecentAppender extends AppenderBase<ILoggingEvent> {
  private final PatternLayout layout;
  private final RecentLog recent;

  RecentAppender(PatternLayout layout, RecentLog recent) {
    this.layout = layout;
    this.recent = recent;
  }

  @Override
  protected void append(ILoggingEvent event) {
    recent.append(layout.doLayout(event), event.getLevel().isGreaterOrEqual(Level.WARN));
  }
}
