package io.github.ajdailey.qcaret.ui.swt.platform;

import java.text.BreakIterator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Caret;

/**
 * Carets on cocoa. After a cluster of more than one char, such as {@code e} and U+0301, SWT 3.135
 * cocoa places a caret at the trailing edge of the cluster's last char, which {@code
 * NSLayoutManager} puts one char to the right (7 pt in Menlo 12 on the macOS CI runners); this
 * class places it at the left edge of the next char's bounds, or at a row's end at the right edge
 * of the cluster's bounds, which rounds up by up to 1 pt. A caret at the start of a wrapped row
 * keeps SWT's place, which is on that row.
 */
final class CocoaCaretPlacement implements CaretPlacement {
  @Override
  public Caret create(StyledText text) {
    return new ClusterCaret(text);
  }

  @Override
  public Point at(StyledText text, int offset) {
    return place(text, offset, text.getLocationAtOffset(offset));
  }

  /**
   * Returns where a caret at an offset goes, given SWT's place for it.
   *
   * @param text the widget
   * @param offset the caret's offset
   * @param swt SWT's place
   * @return {@code swt}, or the edge after the cluster before the offset on its row
   */
  static Point place(StyledText text, int offset, Point swt) {
    int line = text.getLineAtOffset(offset);
    int lineStart = text.getOffsetAtLine(line);
    int inLine = offset - lineStart;
    if (inLine <= 1) {
      return swt;
    }
    String row = text.getLine(line);
    BreakIterator clusters = BreakIterator.getCharacterInstance();
    clusters.setText(row);
    int start = clusters.preceding(Math.min(inLine, row.length()));
    if (start < 0
        || inLine - start < 2
        || inLine - start == 2
            && Character.isSurrogatePair(row.charAt(start), row.charAt(start + 1))) {
      return swt;
    }
    boolean atEnd = inLine >= row.length();
    Rectangle bounds =
        atEnd
            ? text.getTextBounds(lineStart + start, offset - 1)
            : text.getTextBounds(offset, offset);
    if (swt.y < bounds.y || swt.y >= bounds.y + bounds.height) {
      return swt;
    }
    return new Point(atEnd ? bounds.x + bounds.width : bounds.x, swt.y);
  }

  /** A caret that moves to {@link #place} whenever SWT sets its bounds. */
  private static final class ClusterCaret extends Caret {
    private final StyledText text;

    ClusterCaret(StyledText text) {
      super(text, SWT.NONE);
      this.text = text;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
      if (text == null) {
        super.setBounds(x, y, width, height);
        return;
      }
      Point at = place(text, text.getCaretOffset(), new Point(x, y));
      super.setBounds(at.x, at.y, width, height);
    }

    @Override
    protected void checkSubclass() {}
  }
}
