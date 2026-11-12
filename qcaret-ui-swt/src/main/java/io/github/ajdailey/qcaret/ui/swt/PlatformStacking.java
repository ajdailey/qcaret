package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link WindowStacking} through SWT internals. A member that does not resolve, or a call that
 * fails, logs one WARN and leaves the stacking unsupported; nothing throws to the caller.
 */
abstract class PlatformStacking implements WindowStacking {
  private static final Logger LOG = LoggerFactory.getLogger(PlatformStacking.class);

  private boolean supported = true;

  @Override
  public final boolean supported() {
    return supported;
  }

  @Override
  public final void setOnTop(Shell shell, boolean onTop) {
    if (!supported || shell.isDisposed()) {
      return;
    }
    try {
      apply(shell, onTop);
    } catch (ReflectiveOperationException
        | IllegalArgumentException
        | IllegalStateException
        | LinkageError e) {
      fail(e);
    }
  }

  /**
   * Sets the shell's stacking through the resolved members.
   *
   * @param shell a live shell
   * @param onTop true to keep it above
   * @throws ReflectiveOperationException when a reflective call fails
   */
  abstract void apply(Shell shell, boolean onTop) throws ReflectiveOperationException;

  /**
   * Makes the stacking unsupported, with one WARN.
   *
   * @param cause the failure
   */
  final void fail(Throwable cause) {
    if (supported) {
      supported = false;
      LOG.warn("Always on Top is off: a reflective call into SWT internals failed", cause);
    }
  }
}
