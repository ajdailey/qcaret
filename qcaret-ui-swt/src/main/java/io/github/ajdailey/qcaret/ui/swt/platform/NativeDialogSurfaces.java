package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * GTK and cocoa: dialogs and check boxes keep the native theme's colors, which match their page.
 */
final class NativeDialogSurfaces implements DialogSurfaces {
  static final NativeDialogSurfaces INSTANCE = new NativeDialogSurfaces();

  private NativeDialogSurfaces() {}

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
  public Button check(Composite parent) {
    return new Button(parent, SWT.CHECK);
  }
}
