package io.github.ajdailey.qcaret.ui.swt;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * Always on Top on cocoa: the shell's {@code NSWindow} at the floating or the normal window level,
 * through SWT's {@code Shell.window} and {@code internal.cocoa.NSWindow.setLevel}. A shell opened
 * under a floating shell, at any depth, is raised to the floating level when it shows, and returns
 * to the normal level when its top shell does; an {@link SWT#ON_TOP} shell keeps its own level.
 * Native file and message dialogs are sheets or modal panels, which AppKit stacks itself.
 */
final class CocoaStacking extends PlatformStacking {
  /** AppKit {@code NSNormalWindowLevel}. */
  private static final long NORMAL = 0;

  /** AppKit {@code NSFloatingWindowLevel}. */
  private static final long FLOATING = 3;

  private final Field window;
  private final Method setLevel;
  private final Set<Shell> floating = new LinkedHashSet<Shell>();
  private final Set<Shell> raised = new LinkedHashSet<Shell>();
  private final Listener shown =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (event.widget instanceof Shell shell && underFloating(shell)) {
            raise(shell);
          }
        }
      };
  private Display filtered;

  /** Resolves the members; a failure leaves the stacking unsupported. */
  CocoaStacking() {
    Field field = null;
    Method method = null;
    try {
      field = Shell.class.getDeclaredField("window");
      field.setAccessible(true);
      method =
          Class.forName(
                  "org.eclipse.swt.internal.cocoa.NSWindow", false, Shell.class.getClassLoader())
              .getMethod("setLevel", long.class);
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      fail(e);
    }
    window = field;
    setLevel = method;
  }

  @Override
  void apply(Shell shell, boolean onTop) throws ReflectiveOperationException {
    level(shell, onTop ? FLOATING : NORMAL);
    if (onTop) {
      floating.add(shell);
      watch(shell.getDisplay());
      for (Shell child : shell.getShells()) {
        if (child.isVisible()) {
          raise(child);
        }
      }
      return;
    }
    floating.remove(shell);
    prune(raised);
    for (Shell child : new ArrayList<Shell>(raised)) {
      if (!underFloating(child)) {
        raised.remove(child);
        level(child, NORMAL);
      }
    }
  }

  /** Reports whether a floating shell owns the shell, at any depth. */
  private boolean underFloating(Shell shell) {
    if ((shell.getStyle() & SWT.ON_TOP) != 0) {
      return false;
    }
    Object parent = shell.getParent();
    while (parent instanceof Shell owner && !owner.isDisposed()) {
      if (floating.contains(owner)) {
        return true;
      }
      parent = owner.getParent();
    }
    return false;
  }

  private void raise(Shell shell) {
    if (!supported() || raised.contains(shell)) {
      return;
    }
    prune(floating);
    prune(raised);
    try {
      level(shell, FLOATING);
      raised.add(shell);
    } catch (ReflectiveOperationException
        | IllegalArgumentException
        | IllegalStateException
        | LinkageError e) {
      fail(e);
    }
  }

  private static void prune(Set<Shell> shells) {
    Iterator<Shell> it = shells.iterator();
    while (it.hasNext()) {
      if (it.next().isDisposed()) {
        it.remove();
      }
    }
  }

  /** Adds the Show filter that raises a dialog as it opens, once per Display. */
  private void watch(Display display) {
    if (filtered != display) {
      display.addFilter(SWT.Show, shown);
      filtered = display;
    }
  }

  private void level(Shell shell, long level) throws ReflectiveOperationException {
    Object nsWindow = window.get(shell);
    if (nsWindow == null) {
      throw new IllegalStateException("The shell has no NSWindow");
    }
    setLevel.invoke(nsWindow, level);
  }

  /**
   * Returns the shells raised to the floating level, for tests.
   *
   * @return the shells, in the order raised
   */
  List<Shell> raised() {
    return new ArrayList<Shell>(raised);
  }
}
