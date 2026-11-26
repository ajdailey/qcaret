package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.ToIntFunction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Item;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;

/**
 * Column header text of a win32 {@link Table} or {@link Tree} with header colors. SWT 3.135 draws
 * such a header itself, with {@code DrawText} and no {@code DT_END_ELLIPSIS}, 6 px in from each
 * column edge, so a column narrower than its text cuts the text off. While {@link #follow} is on,
 * each column shows its text shortened to fit with {@value #ELLIPSIS} at every resize, with the
 * whole text as its tool tip; {@link HeaderTexts#full} returns the whole text. Confined to the UI
 * thread.
 */
final class Win32HeaderText {
  /** The mark a shortened text ends with, as the native header draws it. */
  static final String ELLIPSIS = "...";

  /** The header's inset from each column edge, in pixels. */
  private static final int INSET_PIXELS = 6;

  private static final String ON = Win32HeaderText.class.getName() + ".on";
  private static final String SHOWN = Win32HeaderText.class.getName() + ".shown";

  private Win32HeaderText() {}

  /**
   * Shortens a list's header texts to their columns while its header has colors, or shows them
   * whole again.
   *
   * @param list a {@link Table} or {@link Tree}; anything else is ignored
   * @param on true while the header has colors
   */
  static void follow(Composite list, boolean on) {
    Item[] columns = columns(list);
    boolean hooked = list.getData(ON) != null;
    list.setData(ON, on);
    for (Item column : columns) {
      if (!hooked) {
        column.addListener(
            SWT.Resize,
            new Listener() {
              @Override
              public void handleEvent(Event event) {
                show(list, column);
              }
            });
      }
      show(list, column);
    }
  }

  private static Item[] columns(Composite list) {
    if (list instanceof Table table) {
      return table.getColumns();
    }
    if (list instanceof Tree tree) {
      return tree.getColumns();
    }
    return new Item[0];
  }

  /** Sets a column's shown text from its whole text. */
  private static void show(Composite list, Item column) {
    String text = column.getText();
    if (!text.equals(column.getData(SHOWN))) {
      column.setData(HeaderTexts.FULL, text);
    }
    String full = HeaderTexts.full(column);
    String shown = full;
    if (Boolean.TRUE.equals(list.getData(ON))) {
      int inset = Math.round(INSET_PIXELS * 100f / PlatformTraits.PLATFORM.layoutZoom(list));
      GC gc = new GC(list);
      try {
        gc.setFont(list.getFont());
        shown =
            fit(
                full,
                width(column) - 2 * inset,
                new ToIntFunction<String>() {
                  @Override
                  public int applyAsInt(String value) {
                    return gc.textExtent(value).x;
                  }
                });
      } finally {
        gc.dispose();
      }
    }
    column.setData(SHOWN, shown);
    if (!shown.equals(text)) {
      column.setText(shown);
    }
    tip(column, shown.equals(full) ? null : full);
  }

  private static int width(Item column) {
    return column instanceof TableColumn c ? c.getWidth() : ((TreeColumn) column).getWidth();
  }

  private static void tip(Item column, String tip) {
    if (column instanceof TableColumn c) {
      c.setToolTipText(tip);
    } else {
      ((TreeColumn) column).setToolTipText(tip);
    }
  }

  /**
   * Returns a text shortened to a width: the whole text when it fits, else its longest prefix
   * followed by {@value #ELLIPSIS} that fits, else empty.
   *
   * @param text the whole text
   * @param room the width available
   * @param extent returns a text's width
   * @return the text to show
   */
  static String fit(String text, int room, ToIntFunction<String> extent) {
    if (extent.applyAsInt(text) <= room) {
      return text;
    }
    for (int end = text.length() - 1; end >= 0; end--) {
      if (end > 0 && Character.isLowSurrogate(text.charAt(end))) {
        continue;
      }
      String shown = text.substring(0, end).stripTrailing() + ELLIPSIS;
      if (extent.applyAsInt(shown) <= room) {
        return shown;
      }
    }
    return "";
  }
}
