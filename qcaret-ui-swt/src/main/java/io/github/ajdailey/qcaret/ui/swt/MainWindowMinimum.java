package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The main window's minimum width, measured from its content: the find bar's widest control, the
 * status bar's shown fields without the notice, and the toolbar's More button, since the toolbar
 * narrows to it. Without it the shell's minimum is its trim, and macOS narrowed the window to 82
 * pt, clipping every control. The find bar's full row is not the minimum, since it is wider than
 * the window sizes in use; its controls past the window's edge clip. Call on the UI thread.
 */
final class MainWindowMinimum {
  private MainWindowMinimum() {}

  /**
   * Raises the shell's minimum width to its content's.
   *
   * @param shell the main window's shell
   * @param more the toolbar's More button, or null
   * @param findBar the find bar
   * @param statusBar the status bar, laid out by a {@link GridLayout}
   * @param notice the status bar's notice, which takes the room left over
   */
  static void apply(
      Shell shell, ToolItem more, Composite findBar, Composite statusBar, Control notice) {
    int width = Math.max(widest(findBar), statusWidth(statusBar, notice));
    if (more != null && !more.isDisposed()) {
      width = Math.max(width, more.getParent().computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
    }
    Point minimum = shell.getMinimumSize();
    shell.setMinimumSize(Math.max(minimum.x, shell.computeTrim(0, 0, width, 0).width), minimum.y);
  }

  /** Returns the widest control in a find bar's rows, with the bar's horizontal margins. */
  static int widest(Composite findBar) {
    int widest = 0;
    for (Control row : findBar.getChildren()) {
      Control[] controls = row instanceof Composite composite ? composite.getChildren() : null;
      if (controls == null) {
        widest = Math.max(widest, row.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
        continue;
      }
      for (Control control : controls) {
        widest = Math.max(widest, control.computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
      }
    }
    GridLayout layout = (GridLayout) findBar.getLayout();
    return widest + 2 * layout.marginWidth;
  }

  /** Returns the width of the status bar's shown fields, the notice left out. */
  static int statusWidth(Composite statusBar, Control notice) {
    GridLayout layout = (GridLayout) statusBar.getLayout();
    int width = 2 * layout.marginWidth;
    int shown = 0;
    for (Control child : statusBar.getChildren()) {
      if (child == notice
          || !child.getVisible()
          || (child.getLayoutData() instanceof GridData data && data.exclude)) {
        continue;
      }
      width += child.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
      shown++;
    }
    return width + Math.max(0, shown - 1) * layout.horizontalSpacing;
  }
}
