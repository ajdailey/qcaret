package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import org.eclipse.swt.printing.PrintDialog;
import org.eclipse.swt.printing.PrinterData;
import org.eclipse.swt.widgets.Shell;

/**
 * Creates the {@link PrintDialog} of File > Print and Print Selection. With a selection the dialog
 * opens with {@link PrinterData#SELECTION} chosen, except where {@link
 * PlatformTraits#printDialogOmitsSelection} holds: there it opens on its default scope, so the job
 * prints what the dialog shows.
 */
final class PrintDialogs {
  private PrintDialogs() {}

  /**
   * Creates the dialog, not yet open.
   *
   * @param parent the owning shell
   * @param selected true when the document has a non-empty selection
   * @param traits the platform's traits
   * @return the dialog
   */
  static PrintDialog create(Shell parent, boolean selected, PlatformTraits traits) {
    PrintDialog dialog = new PrintDialog(parent);
    if (selected && !traits.printDialogOmitsSelection()) {
      dialog.setScope(PrinterData.SELECTION);
    }
    return dialog;
  }
}
