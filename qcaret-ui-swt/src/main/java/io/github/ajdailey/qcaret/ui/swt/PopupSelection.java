package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.platform.Contrast;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;

/**
 * Draws a pop-up list's selected row in {@link Palette#DARK}'s selection colors while the table's
 * background is dark, on {@link SWT#EraseItem}; on a light background the native selection stays.
 * Confined to the UI thread.
 */
final class PopupSelection {
  /** The selected row's background. */
  static final Color BACKGROUND = color(Palette.DARK.selection());

  /** The selected row's text, raised to {@link Contrast#LINK} on {@link #BACKGROUND}. */
  static final Color TEXT =
      new Color(
          Contrast.readable(color(Palette.DARK.selectionText()).getRGB(), BACKGROUND.getRGB()));

  private PopupSelection() {}

  /**
   * Draws the table's selected row from now on.
   *
   * @param table a single-selection pop-up list
   */
  static void draw(final Table table) {
    table.addListener(
        SWT.EraseItem,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if ((event.detail & SWT.SELECTED) != 0 && ToolIcons.dark(table.getBackground())) {
              event.gc.setBackground(BACKGROUND);
              event.gc.fillRectangle(event.x, event.y, event.width, event.height);
              event.gc.setForeground(TEXT);
              event.detail &= ~(SWT.SELECTED | SWT.HOT);
            }
          }
        });
  }

  private static Color color(Rgb rgb) {
    return new Color(new RGB(rgb.red(), rgb.green(), rgb.blue()));
  }
}
