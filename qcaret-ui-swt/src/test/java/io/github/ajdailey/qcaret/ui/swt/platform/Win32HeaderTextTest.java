package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.ToIntFunction;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shortened header texts: the fit, and a narrowed and widened column on any toolkit. */
class Win32HeaderTextTest {
  /** Every char 10 wide. */
  private static final ToIntFunction<String> TEN =
      new ToIntFunction<String>() {
        @Override
        public int applyAsInt(String value) {
          return 10 * value.length();
        }
      };

  @Test
  void aTextThatFitsStaysWholeAndALongerOneEndsInAnEllipsis() {
    assertEquals("Comment", Win32HeaderText.fit("Comment", 70, TEN));
    assertEquals("Com...", Win32HeaderText.fit("Comment", 69, TEN));
    assertEquals("C...", Win32HeaderText.fit("Comment", 40, TEN));
    assertEquals("...", Win32HeaderText.fit("Comment", 30, TEN));
    assertEquals("", Win32HeaderText.fit("Comment", 29, TEN));
    assertEquals("Line...", Win32HeaderText.fit("Line text", 70, TEN));
  }

  @Tag("swt")
  @Test
  void aNarrowedColumnShowsAnEllipsisAndItsWholeTextAsATip() {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      shell.setLayout(null);
      Table table = new Table(shell, SWT.NONE);
      table.setBounds(0, 0, 400, 200);
      table.setHeaderVisible(true);
      TableColumn column = new TableColumn(table, SWT.LEFT);
      column.setText("Comment");
      column.setWidth(300);
      Win32HeaderText.follow(table, true);
      assertEquals("Comment", column.getText());
      assertNull(column.getToolTipText());
      column.setWidth(30);
      assertTrue(column.getText().endsWith(Win32HeaderText.ELLIPSIS), column.getText());
      assertEquals("Comment", HeaderTexts.full(column));
      assertEquals("Comment", column.getToolTipText());
      column.setWidth(300);
      assertEquals("Comment", column.getText());
      column.setText("Note");
      column.setWidth(20);
      assertEquals("Note", HeaderTexts.full(column));
      Win32HeaderText.follow(table, false);
      assertEquals("Note", column.getText());
      assertNull(column.getToolTipText());
    } finally {
      display.dispose();
    }
  }
}
