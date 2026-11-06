package io.github.ajdailey.qcaret.ui.swt.editor;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Startup timing marker: the benchmark harness and the smoke test wait for its DEBUG line. */
final class FirstTextPaint {
  private static final Logger LOG = LoggerFactory.getLogger(FirstTextPaint.class);
  private static final AtomicBoolean PAINTED = new AtomicBoolean();

  private FirstTextPaint() {}

  /** Logs the line on the first call in this process. */
  static void painted() {
    if (PAINTED.compareAndSet(false, true)) {
      LOG.debug("First paint of file text");
    }
  }
}
