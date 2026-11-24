package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Supplier;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/** Printing on GTK 4, where {@code PrintDialog.open} fails: a PDF file is chosen instead. */
final class Gtk4PrintSupport extends Gtk3PrintSupport {
  @Override
  public PrinterData choose(
      Shell parent, String jobName, boolean selected, Supplier<PrinterData> pdf) {
    return pdf.get();
  }
}
