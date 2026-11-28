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
 * The main window's minimum width, measured from its content: the find row from its left edge
 * through the find field and its Show history button, the status bar's shown fields without the
 * notice, and the toolbar's More button, since the toolbar narrows to it. Without it the shell's
 * minimum is its trim, and macOS narrowed the window to 82 pt, clipping every control. The find
 * row's controls after the field are not in the minimum, since the full row is wider than the
 * window sizes in use; they clip past the window's edge. Call on the UI thread.
 */
final class MainWindowMinimum {
  private MainWindowMinimum() {}

  /**
   * Raises the shell's minimum width to its content's.
   *
   * @param shell the main window's shell
   * @param more the toolbar's More button, or null
   * @param findField the find field's control, in a find bar row laid out by a {@link GridLayout}
   * @param statusBar the status bar, laid out by a {@link GridLayout}
   * @param notice the status bar's notice, which takes the room left over
   */
  static void apply(
      Shell shell, ToolItem more, Control findField, Composite statusBar, Control notice) {
    int width = Math.max(throughField(findField), statusWidth(statusBar, notice));
    if (more != null && !more.isDisposed()) {
      width = Math.max(width, more.getParent().computeSize(SWT.DEFAULT, SWT.DEFAULT).x);
    }
    Point minimum = shell.getMinimumSize();
    shell.setMinimumSize(Math.max(minimum.x, shell.computeTrim(0, 0, width, 0).width), minimum.y);
  }

  /**
   * Returns the find bar's width from its left edge through a field's right edge: the row's
   * controls up to the field at their {@link GridLayout} widths, and the bar's margins on both
   * sides.
   *
   * @param field a control in a row of the find bar
   * @return the width in pixels
   */
  static int throughField(Control field) {
    Composite row = field.getParent();
    GridLayout rowLayout = (GridLayout) row.getLayout();
    int width = rowLayout.marginLeft + rowLayout.marginWidth;
    for (Control child : row.getChildren()) {
      int hint = SWT.DEFAULT;
      if (child.getLayoutData() instanceof GridData data) {
        hint = data.widthHint;
        width += data.horizontalIndent;
      }
      width += child.computeSize(hint, SWT.DEFAULT).x;
      if (child == field) {
        break;
      }
      width += rowLayout.horizontalSpacing;
    }
    GridLayout barLayout = (GridLayout) row.getParent().getLayout();
    return width + 2 * barLayout.marginWidth + barLayout.marginLeft + barLayout.marginRight;
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
