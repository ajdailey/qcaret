package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The JDK's logging shutdown hook calls {@code reset()}, which would remove the handlers while
 * QCaret's shutdown hook still logs its forced-exit line. A retained manager ignores it.
 */
class RetainingLogManagerTest {
  private static final class Kept extends Handler {
    @Override
    public void publish(LogRecord record) {
      // Not used.
    }

    @Override
    public void flush() {
      // Not used.
    }

    @Override
    public void close() {
      // Not used.
    }
  }

  @Test
  void resetDropsHandlersOnlyUntilRetained() {
    Handler handler = new Kept();
    RetainingLogManager manager = new RetainingLogManager();
    Logger logger = new Logger("mock.retained", null) {};
    manager.addLogger(logger);
    logger.addHandler(handler);
    logger.setLevel(Level.FINE);
    manager.reset();
    assertEquals(0, logger.getHandlers().length);
    logger.addHandler(handler);
    logger.setLevel(Level.FINE);
    manager.retain();
    manager.reset();
    assertArrayEquals(new Handler[] {handler}, logger.getHandlers());
    assertEquals(Level.FINE, logger.getLevel());
  }
}
