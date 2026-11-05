package io.github.ajdailey.qcaret.launcher;

import java.util.logging.LogManager;

/**
 * The JVM's {@code LogManager} when {@link LogConfigurator} installs it ({@code
 * java.util.logging.manager}). After {@link #retain}, {@link #reset} does nothing, so the JDK's
 * logging shutdown hook leaves the handlers attached while QCaret's own shutdown hook still logs.
 * Every handler flushes per record, so nothing waits for a close.
 */
public final class RetainingLogManager extends LogManager {
  private volatile boolean retained;

  /** Creates the manager; {@code LogManager} instantiates it by name. */
  public RetainingLogManager() {
    super();
  }

  /** Makes every later {@link #reset} a no-op. */
  void retain() {
    retained = true;
  }

  @Override
  public void reset() {
    if (!retained) {
      super.reset();
    }
  }
}
