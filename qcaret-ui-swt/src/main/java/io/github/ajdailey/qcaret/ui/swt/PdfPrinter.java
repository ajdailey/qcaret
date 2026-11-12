package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import org.eclipse.swt.SWT;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

/**
 * File > Print where {@code PrintDialog} does not open: a save dialog names a PDF file, and GTK's
 * Print to File printer writes every page, one copy.
 */
final class PdfPrinter {
  /** The {@link PrinterData#driver} of GTK's Print to File printer. */
  static final String FILE_BACKEND = "GtkPrintBackendFile";

  /** The save dialog's file name, as GTK's print dialog proposes. */
  static final String NAME = "output.pdf";

  private PdfPrinter() {}

  /**
   * Asks for the PDF file with a save dialog.
   *
   * @param shell the dialog's parent
   * @return the path, or null when canceled
   */
  static String choose(Shell shell) {
    FileDialog dialog = new FileDialog(shell, SWT.SAVE);
    dialog.setText(UiText.PRINT_TO_PDF.text());
    dialog.setOverwrite(true);
    dialog.setFilterExtensions(new String[] {"*.pdf"});
    dialog.setFileName(NAME);
    return dialog.open();
  }

  /**
   * Returns the Print to File printer set to write a file. Must run on the UI thread, as {@code new
   * Printer} does.
   *
   * @param file the PDF path
   * @return the printer data
   * @throws IllegalArgumentException when the print system lists no Print to File printer
   */
  static PrinterData printer(String file) {
    for (PrinterData data : Printer.getPrinterList()) {
      if (FILE_BACKEND.equals(data.driver)) {
        data.printToFile = true;
        data.fileName = file;
        return data;
      }
    }
    throw new IllegalArgumentException("No " + FILE_BACKEND + " printer");
  }
}
