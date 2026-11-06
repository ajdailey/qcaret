package io.github.ajdailey.qcaret.ui.swt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The check that sends a row with wider bold glyphs to the measured positions. */
class EditorLayoutGridTest {
  private static final String ROW = "abcdefghij klmnopqrst";

  @Test
  void aRowAtTheAdvanceIsOnTheGrid() {
    assertTrue(EditorLayout.onGrid(722, 100, 7.22));
    assertTrue(EditorLayout.onGrid(725, 100, 7.22));
    assertTrue(EditorLayout.onGrid(0, 0, 7.22));
  }

  @Test
  void aRowHalfAColumnOffTheAdvanceIsNot() {
    assertFalse(EditorLayout.onGrid(726, 100, 7.22));
    assertFalse(EditorLayout.onGrid(718, 100, 7.22));
    assertFalse(EditorLayout.onGrid(740, 100, 7.0));
  }

  /** The widget's glyphs are narrower here than the advance, as a row of other width would be. */
  @Test
  @Tag("swt")
  void aRowOffTheGridMapsByMeasuredPositions() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      StyledText text = new StyledText(shell, SWT.NONE);
      text.setText(ROW);
      text.setSize(800, 100);
      final double advance = text.getLocationAtOffset(ROW.length()).x / (double) ROW.length() + 2;
      Document document =
          Document.of(
              ROW,
              TextEncoding.of(StandardCharsets.UTF_8, false),
              LineEnding.LF,
              UndoLimits.DEFAULTS);
      DisplayProjection projection =
          new DisplayProjection(
              document, DisplayProjection.layout(document.text(), 1000, new CancellationToken()));
      EditorLayout layout =
          new EditorLayout(
              text,
              projection,
              new Supplier<ColumnMetrics>() {
                @Override
                public ColumnMetrics get() {
                  return new ColumnMetrics(advance);
                }
              });
      for (int column = 0; column < ROW.length(); column++) {
        assertEquals(column, layout.columnAt(0, text.getLocationAtOffset(column).x));
      }
    } finally {
      display.dispose();
    }
  }
}
