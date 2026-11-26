package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A caret after {@code e} and U+0301 is drawn where a caret after the precomposed U+00E9 is, inside
 * a row and at its end. On cocoa SWT 3.135 alone puts it one char to the right; the failure message
 * names SWT's own x.
 */
@Tag("swt")
class CaretPlacementTest {
  /** Rows: a combining pair inside and at the end, then the same with U+00E9. */
  private static final String TEXT = "ae\u0301x\nae\u0301\na\u00e9x\na\u00e9";

  private Display display;
  private Shell shell;
  private Font font;
  private StyledText text;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
    text = new StyledText(shell, SWT.MULTI);
    text.setBounds(0, 0, 400, 200);
    font =
        new Font(
            display,
            PlatformTraits.PLATFORM.monospaceFamily(),
            PlatformTraits.PLATFORM.monospaceSize(),
            SWT.NORMAL);
    text.setFont(font);
    text.setText(TEXT);
    shell.open();
  }

  @AfterEach
  void close() {
    shell.dispose();
    font.dispose();
    display.dispose();
  }

  @Test
  void aCaretAfterACombiningMarkIsAtTheClusterEdge() {
    CaretPlacement placement = PlatformTraits.PLATFORM.caretPlacement();
    assertNear(at(2, 2), placement.at(text, at(0, 3)), at(0, 3), "inside a row");
    assertNear(at(3, 2), placement.at(text, at(1, 3)), at(1, 3), "at a row's end");
    Caret caret = placement.create(text);
    text.setCaret(caret);
    text.setCaretOffset(at(0, 3));
    assertNear(at(2, 2), caret.getLocation(), at(0, 3), "the widget's caret");
  }

  @Test
  void theCocoaPlacementMatchesAClusterOnEveryToolkit() {
    Point inside = CocoaCaretPlacement.place(text, at(0, 3), text.getLocationAtOffset(at(0, 3)));
    assertNear(at(2, 2), inside, at(0, 3), "cocoa place inside a row");
    Point plain = CocoaCaretPlacement.place(text, at(2, 2), text.getLocationAtOffset(at(2, 2)));
    assertTrue(
        plain.equals(text.getLocationAtOffset(at(2, 2))), "a one-char cluster keeps " + plain);
  }

  private int at(int row, int column) {
    return text.getOffsetAtLine(row) + column;
  }

  private void assertNear(int expectedOffset, Point actual, int offset, String what) {
    int expected = text.getLocationAtOffset(expectedOffset).x;
    assertTrue(
        Math.abs(expected - actual.x) <= 1,
        what
            + ": caret at "
            + actual.x
            + ", U+00E9 caret at "
            + expected
            + ", SWT location "
            + text.getLocationAtOffset(offset).x);
  }
}
