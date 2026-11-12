package io.github.ajdailey.qcaret.ui.swt;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.widgets.Shell;

/**
 * Always on Top on GTK 3 under X11: {@code gtk_window_set_keep_above} on the shell's GtkWindow,
 * through SWT's {@code Shell.shellHandle} and {@code internal.gtk3.GTK3}. GTK sets {@code
 * _NET_WM_STATE_ABOVE} itself on a window it maps and asks the window manager to change it on a
 * mapped one, so a live toggle needs an EWMH window manager.
 */
final class GtkX11Stacking extends PlatformStacking {
  private final Field shellHandle;
  private final Method keepAbove;

  /** Resolves the members; a failure leaves the stacking unsupported. */
  GtkX11Stacking() {
    Field field = null;
    Method method = null;
    try {
      field = Shell.class.getDeclaredField("shellHandle");
      field.setAccessible(true);
      method =
          Class.forName("org.eclipse.swt.internal.gtk3.GTK3", false, Shell.class.getClassLoader())
              .getMethod("gtk_window_set_keep_above", long.class, boolean.class);
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      fail(e);
    }
    shellHandle = field;
    keepAbove = method;
  }

  @Override
  void apply(Shell shell, boolean onTop) throws ReflectiveOperationException {
    keepAbove.invoke(null, shellHandle.getLong(shell), onTop);
  }
}
