package io.github.ajdailey.qcaret.ui.swt;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Always on Top on win32: {@code SetWindowPos} with {@code HWND_TOPMOST} or {@code HWND_NOTOPMOST}
 * on the shell's window, through SWT's {@code Control.handle} and {@code internal.win32.OS}. The
 * window keeps its position, size and activation. Windows makes a topmost window's owned windows,
 * the dialogs it opens, topmost with it.
 */
final class Win32Stacking extends PlatformStacking {
  /** Win32 {@code HWND_TOPMOST}. */
  private static final long TOPMOST = -1;

  /** Win32 {@code HWND_NOTOPMOST}. */
  private static final long NOT_TOPMOST = -2;

  /** Win32 {@code SWP_NOSIZE | SWP_NOMOVE | SWP_NOACTIVATE}. */
  private static final int KEEP = 0x1 | 0x2 | 0x10;

  private final Field handle;
  private final Method setWindowPos;

  /** Resolves the members; a failure leaves the stacking unsupported. */
  Win32Stacking() {
    Field field = null;
    Method method = null;
    try {
      field = Control.class.getField("handle");
      method =
          Class.forName("org.eclipse.swt.internal.win32.OS", false, Shell.class.getClassLoader())
              .getMethod(
                  "SetWindowPos",
                  long.class,
                  long.class,
                  int.class,
                  int.class,
                  int.class,
                  int.class,
                  int.class);
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      fail(e);
    }
    handle = field;
    setWindowPos = method;
  }

  @Override
  void apply(Shell shell, boolean onTop) throws ReflectiveOperationException {
    Object done =
        setWindowPos.invoke(
            null, handle.getLong(shell), onTop ? TOPMOST : NOT_TOPMOST, 0, 0, 0, 0, KEEP);
    if (!Boolean.TRUE.equals(done)) {
      throw new IllegalStateException("SetWindowPos failed");
    }
  }
}
