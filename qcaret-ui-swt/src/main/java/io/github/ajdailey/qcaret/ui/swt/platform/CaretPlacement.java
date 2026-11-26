package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;

/**
 * Where an editor's carets go, one implementation per platform from {@link
 * PlatformTraits#caretPlacement}. SWT 3.135 cocoa steps back one char, not one cluster, to place a
 * caret after the char before it, so after {@code e} and U+0301 it draws one char to the right;
 * {@link CocoaCaretPlacement} places such a caret at the cluster's right edge, and {@link
 * NativeCaretPlacement} keeps SWT's location. Call on the UI thread.
 */
public interface CaretPlacement {
  /**
   * Creates the caret a widget draws at its caret offset.
   *
   * @param text the widget
   * @return the caret, not yet set on the widget
   */
  Caret create(StyledText text);

  /**
   * Returns where a caret at an offset is drawn.
   *
   * @param text the widget
   * @param offset an offset in the widget
   * @return the caret's top-left corner, as {@link StyledText#getLocationAtOffset} gives it
   */
  Point at(StyledText text, int offset);
}
