package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * GTK and cocoa: dialogs and check boxes keep the native theme's colors, which match their page.
 */
final class NativeDialogColors implements DialogColors {
  static final NativeDialogColors INSTANCE = new NativeDialogColors();

  private NativeDialogColors() {}

  @Override
  public void apply(Shell shell) {
    // The native theme colors the dialog.
  }

  @Override
  public boolean colored(Control control) {
    return false;
  }

  @Override
  public Color background(Shell owner) {
    return owner.getBackground();
  }

  @Override
  public void gridLines(Table table) {
    table.setLinesVisible(true);
  }
}
