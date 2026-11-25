package io.github.ajdailey.qcaret.ui.swt.platform;

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
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Always on Top on cocoa: the shell's {@code NSWindow} at the floating or the normal window level,
 * through SWT's {@code Shell.window} and {@code internal.cocoa.NSWindow.setLevel}. A shell opened
 * under a floating shell, at any depth, is raised to the floating level when it shows, and returns
 * to the normal level when its top shell does; an {@link SWT#ON_TOP} shell keeps its own level.
 * Native file and message dialogs are sheets or modal panels, which AppKit stacks itself.
 *
 * <p>SWT makes a shell with a parent an AppKit child window of it, and AppKit takes the parent off
 * screen when a child window miniaturizes. {@link #minimize} removes a tool window from its owner's
 * children ({@code NSWindow.removeChildWindow}) before it miniaturizes. A Display {@code
 * SWT.Iconify} filter covers the title bar's button: it removes the minimized tool window from the
 * children and brings the owner back ({@code orderFront}). Either way the tool window rejoins the
 * children above the owner ({@code addChildWindow}) when it is restored. The filter goes on the
 * Display current when the stacking is created, and on the Display of each shell passed to {@link
 * #minimize} or {@link #setOnTop}. Unsupported, {@link #minimize} and the filter do nothing: a tool
 * window minimized while still a child window would take its owner off screen.
 */
final class CocoaStacking extends PlatformStacking {
  /** AppKit {@code NSNormalWindowLevel}. */
  private static final long NORMAL = 0;

  /** AppKit {@code NSFloatingWindowLevel}. */
  private static final long FLOATING = 3;

  /** AppKit {@code NSWindowAbove}. */
  private static final long ABOVE = 1;

  private final Field window;
  private final Method setLevel;
  private final Method removeChild;
  private final Method addChild;
  private final Method orderFront;
  private final Set<Shell> detached = new LinkedHashSet<Shell>();
  private final Listener iconified =
      new Listener() {
        @Override
        public void handleEvent(Event event) {
          if (event.widget instanceof Shell tool) {
            bringOwnerBack(tool);
          }
        }
      };
  private Display iconifyFiltered;
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
    Method remove = null;
    Method add = null;
    Method front = null;
    try {
      field = Shell.class.getDeclaredField("window");
      field.setAccessible(true);
      Class<?> nsWindow =
          Class.forName(
              "org.eclipse.swt.internal.cocoa.NSWindow", false, Shell.class.getClassLoader());
      method = nsWindow.getMethod("setLevel", long.class);
      remove = nsWindow.getMethod("removeChildWindow", nsWindow);
      add = nsWindow.getMethod("addChildWindow", nsWindow, long.class);
      front =
          nsWindow.getMethod(
              "orderFront",
              Class.forName(
                  "org.eclipse.swt.internal.cocoa.id", false, Shell.class.getClassLoader()));
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      fail(e);
    }
    window = field;
    setLevel = method;
    removeChild = remove;
    addChild = add;
    orderFront = front;
    Display display = Display.getCurrent();
    if (display != null) {
      watchIconify(display);
    }
  }

  @Override
  void apply(Shell shell, boolean onTop) throws ReflectiveOperationException {
    watchIconify(shell.getDisplay());
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

  /** Adds the Iconify filter that keeps an owner on screen, once per Display. */
  private void watchIconify(Display display) {
    if (iconifyFiltered != display && !display.isDisposed()) {
      display.addFilter(SWT.Iconify, iconified);
      iconifyFiltered = display;
    }
  }

  private void level(Shell shell, long level) throws ReflectiveOperationException {
    setLevel.invoke(nsWindow(shell), level);
  }

  /**
   * Returns the shells raised to the floating level, for tests.
   *
   * @return the shells, in the order raised
   */
  List<Shell> raised() {
    return new ArrayList<Shell>(raised);
  }

  @Override
  public void explainUnsupported(MenuItem item) {}

  @Override
  public void minimize(Shell shell) {
    watchIconify(shell.getDisplay());
    if (!supported() || shell.getMinimized()) {
      return;
    }
    if (shell.isVisible() && shell.getParent() instanceof Shell owner) {
      try {
        removeChild.invoke(nsWindow(owner), nsWindow(shell));
        rejoinOnRestore(shell, owner);
      } catch (ReflectiveOperationException
          | IllegalArgumentException
          | IllegalStateException
          | LinkageError e) {
        fail(e);
        return;
      }
    }
    shell.setMinimized(true);
  }

  /**
   * Brings a minimized tool window's owner back when the tool window is still among its children:
   * minimized through the title bar's button.
   */
  private void bringOwnerBack(Shell tool) {
    prune(detached);
    if (!supported()
        || detached.contains(tool)
        || !(tool.getParent() instanceof Shell owner)
        || owner.isDisposed()
        || !owner.isVisible()
        || owner.getMinimized()) {
      return;
    }
    try {
      Object ownerWindow = nsWindow(owner);
      removeChild.invoke(ownerWindow, nsWindow(tool));
      rejoinOnRestore(tool, owner);
      orderFront.invoke(ownerWindow, (Object) null);
    } catch (ReflectiveOperationException
        | IllegalArgumentException
        | IllegalStateException
        | LinkageError e) {
      fail(e);
    }
  }

  /** Adds the tool window back to its owner's children once, when it is restored. */
  private void rejoinOnRestore(final Shell tool, final Shell owner) {
    detached.add(tool);
    tool.addListener(
        SWT.Deiconify,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            tool.removeListener(SWT.Deiconify, this);
            detached.remove(tool);
            if (!supported()
                || owner.isDisposed()
                || !owner.isVisible()
                || owner.getMinimized()
                || !tool.isVisible()) {
              return;
            }
            try {
              addChild.invoke(nsWindow(owner), nsWindow(tool), ABOVE);
            } catch (ReflectiveOperationException
                | IllegalArgumentException
                | IllegalStateException
                | LinkageError e) {
              fail(e);
            }
          }
        });
  }

  private Object nsWindow(Shell shell) throws ReflectiveOperationException {
    Object nsWindow = window.get(shell);
    if (nsWindow == null) {
      throw new IllegalStateException("The shell has no NSWindow");
    }
    return nsWindow;
  }
}
