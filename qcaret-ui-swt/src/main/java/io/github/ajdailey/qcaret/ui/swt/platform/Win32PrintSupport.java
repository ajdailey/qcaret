package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/** Printing on win32: the dialog always opens on its default scope. */
final class Win32PrintSupport implements PrintSupport {
  @Override
  public boolean noPrinter() {
    return Printer.getPrinterList().length == 0;
  }

  @Override
  public PrinterData choose(
      Shell parent,
      String jobName,
      boolean selected,
      Function<PrintDialog, PrinterData> open,
      Supplier<PrinterData> pdf) {
    return open.apply(dialog(parent, selected));
  }

  @Override
  public PrintDialog dialog(Shell parent, boolean selected) {
    return new PrintDialog(parent);
  }
}
