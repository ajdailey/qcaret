package io.github.ajdailey.qcaret.ui.swt.editor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.text.ControlChars;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Text after boxes lands at its column: a row of eight boxes and one of sixteen zeros put the
 * following char at the same x within 1 px. The pom also runs this class with {@code
 * -Dswt.autoScale=150}, where a fixed whole-point box width moved the text 1 to 1.5 px per box on
 * win32.
 */
@Tag("swt")
class BoxDriftSurfaceTest {
  private static final int BOXES = 8;

  private Display display;
  private Shell shell;
  private Font font;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
  }

  @AfterEach
  void close() {
    shell.dispose();
    if (font != null) {
      font.dispose();
    }
    display.dispose();
  }

  @Test
  void textAfterARunOfBoxesAndAfterSeparateBoxesStaysAtItsColumn() {
    String run = "a" + "\u0001".repeat(BOXES) + "x";
    StringBuilder apart = new StringBuilder("a");
    for (int i = 0; i < BOXES; i++) {
      apart.append('\u0002').append('b');
    }
    String plain = "a" + "0".repeat(2 * BOXES) + "x";
    String plainApart = "a" + "00b".repeat(BOXES);
    String[] original = {run, apart.toString(), plain, plainApart};
    StyledText text = new StyledText(shell, SWT.MULTI);
    text.setBounds(0, 0, 900, 200);
    font =
        new Font(
            display,
            PlatformTraits.PLATFORM.monospaceFamily(),
            PlatformTraits.PLATFORM.monospaceSize(),
            SWT.NORMAL);
    text.setFont(font);
    StringBuilder content = new StringBuilder();
    for (String row : original) {
      content.append(ControlChars.shown(row)).append('\n');
    }
    text.setText(content.toString());
    ControlBoxes boxes =
        new ControlBoxes(
            text,
            new ControlBoxes.Rows() {
              @Override
              public String original(int rowOffset) {
                return original[text.getLineAtOffset(rowOffset)];
              }

              @Override
              public Color background(int rowOffset) {
                return null;
              }
            });
    text.addLineStyleListener(
        new LineStyleListener() {
          @Override
          public void lineGetStyle(LineStyleEvent event) {
            event.styles = boxes.styles(event.lineOffset, event.lineText, event.styles);
          }
        });
    boxes.setFont(font, ControlBoxes.cellWidth(text, font));
    text.redraw();
    shell.open();
    int zoom = PlatformTraits.PLATFORM.layoutZoom(text);
    // One pixel in points, plus a point for rounding each position to points.
    double slack = 100.0 / zoom + 1;
    assertAt(text, 0, run.length() - 1, 2, plain.length() - 1, slack, zoom);
    for (int i = 0; i < BOXES; i++) {
      int at = 2 + 2 * i;
      assertAt(text, 1, at, 3, 3 + 3 * i, slack, zoom);
    }
  }

  /** Checks a char's x on one row against a char's x on another. */
  private static void assertAt(
      StyledText text, int row, int column, int plainRow, int plainColumn, double slack, int zoom) {
    int x = text.getLocationAtOffset(text.getOffsetAtLine(row) + column).x;
    int expected = text.getLocationAtOffset(text.getOffsetAtLine(plainRow) + plainColumn).x;
    assertTrue(
        Math.abs(x - expected) <= slack,
        "row "
            + row
            + " char "
            + column
            + " at "
            + x
            + " pt, column at "
            + expected
            + " pt, zoom "
            + zoom);
  }
}
