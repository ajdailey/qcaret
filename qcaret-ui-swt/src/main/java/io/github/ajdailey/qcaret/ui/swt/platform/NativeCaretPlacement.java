package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;

/** Carets on win32 and GTK: where SWT places them. */
final class NativeCaretPlacement implements CaretPlacement {
  @Override
  public Caret create(StyledText text) {
    return new Caret(text, SWT.NONE);
  }

  @Override
  public Point at(StyledText text, int offset) {
    return text.getLocationAtOffset(offset);
  }
}
