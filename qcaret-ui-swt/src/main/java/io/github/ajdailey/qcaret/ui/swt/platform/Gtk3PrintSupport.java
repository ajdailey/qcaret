package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Function;
import java.util.function.Supplier;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/** Printing on GTK 3: no printer check, and the dialog opens on a selection. */
class Gtk3PrintSupport implements PrintSupport {
  @Override
  public final boolean noPrinter() {
    return false;
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
  public final PrintDialog dialog(Shell parent, boolean selected) {
    return selectionDialog(parent, selected);
  }

  /**
   * Creates a dialog on the Selection scope when there is a selection.
   *
   * @param parent the owning shell
   * @param selected true when the document has a non-empty selection
   * @return the dialog
   */
  static PrintDialog selectionDialog(Shell parent, boolean selected) {
    PrintDialog dialog = new PrintDialog(parent);
    if (selected) {
      dialog.setScope(PrinterData.SELECTION);
    }
    return dialog;
  }
}
