package io.github.ajdailey.qcaret.ui.swt;

import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/**
 * Creates File > Print's {@link PrintDialog}. With a selection the dialog opens with {@link
 * PrinterData#SELECTION} chosen: the Windows dialog offers Selection only when it opens with it. A
 * PDF name goes in {@link PrinterData#fileName}, which the macOS dialog passes to its save sheet.
 */
final class PrintDialogs {
  private PrintDialogs() {}

  /**
   * Creates the dialog, not yet open.
   *
   * @param parent the owning shell
   * @param selected true when the document has a non-empty selection
   * @param pdfName the proposed PDF file name, or null for none
   * @return the dialog
   */
  static PrintDialog create(Shell parent, boolean selected, String pdfName) {
    PrintDialog dialog = new PrintDialog(parent);
    if (selected) {
      dialog.setScope(PrinterData.SELECTION);
    }
    if (pdfName != null) {
      dialog.getPrinterData().fileName = pdfName;
    }
    return dialog;
  }
}
