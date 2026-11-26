package io.github.ajdailey.qcaret.ui.swt.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.theme.Palette;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A virtual row whose data is requested after the model emptied without a refresh, as when the
 * search controller is disposed while the window closes, loads nothing instead of throwing.
 */
@Tag("swt")
class ResultsDockSurfaceTest {
  private Display display;

  @BeforeEach
  void open() {
    display = new Display();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void aRowPendingWhenTheModelEmptiesLoadsNothing() {
    Shell shell = new Shell(display);
    ResultsDock dock = new ResultsDock(shell, Palette.LIGHT);
    ResultRowsTest.Model model = new ResultRowsTest.Model(new long[] {1}, new int[][] {{2}});
    dock.setInput(model);
    model.ids = new long[0];
    model.matches = new int[0][];
    assertEquals("", dock.text(0));
  }
}
