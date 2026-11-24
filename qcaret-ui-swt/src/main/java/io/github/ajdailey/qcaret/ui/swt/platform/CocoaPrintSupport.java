package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Supplier;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.Printer;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/** Printing on cocoa: the job is named before the dialog opens. */
final class CocoaPrintSupport implements PrintSupport {
  private CocoaPrintJobNaming naming;

  @Override
  public boolean noPrinter() {
    return Printer.getPrinterList().length == 0;
  }

  @Override
  public PrinterData choose(
      Shell parent, String jobName, boolean selected, Supplier<PrinterData> pdf) {
    if (naming == null) {
      naming = new CocoaPrintJobNaming();
    }
    naming.name(jobName);
    return dialog(parent, selected).open();
  }

  @Override
  public PrintDialog dialog(Shell parent, boolean selected) {
    return Gtk3PrintSupport.selectionDialog(parent, selected);
  }
}
