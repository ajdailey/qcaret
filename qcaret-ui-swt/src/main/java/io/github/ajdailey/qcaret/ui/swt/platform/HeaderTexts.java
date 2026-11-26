package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Item;

/** The whole text of a table or tree column whose header shows it shortened. */
public final class HeaderTexts {
  /** Data key on a column holding its whole text while the header shows it shortened. */
  static final String FULL = HeaderTexts.class.getName() + ".full";

  private HeaderTexts() {}

  /**
   * Returns a column's whole header text.
   *
   * @param column a {@code TableColumn} or {@code TreeColumn}
   * @return the text the column was given, which its header may show shortened
   */
  public static String full(Item column) {
    Object full = column.getData(FULL);
    return full instanceof String text ? text : column.getText();
  }
}
