package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

/**
 * Dialog colors where a platform's native theme leaves a dialog body unmatched to its title bar and
 * buttons, and check boxes readable on their page. One class per platform; {@link
 * PlatformTraits#dialogSurfaces} picks it.
 */
public interface DialogSurfaces {
  /**
   * Returns the running platform's implementation.
   *
   * @return {@link PlatformTraits#dialogSurfaces} of {@link PlatformTraits#PLATFORM}
   */
  static DialogSurfaces platform() {
    return PlatformTraits.PLATFORM.dialogSurfaces();
  }

  /**
   * Colors a dialog shell and every control later created in it. Call right after creating the
   * shell, before its children.
   *
   * @param shell a dialog shell, never the main window
   */
  void apply(Shell shell);

  /**
   * Reports whether a control sits in a shell {@link #apply} colored.
   *
   * @param control the control
   * @return true when its shell carries dialog colors
   */
  boolean colored(Control control);

  /**
   * Returns the background of a dialog owned by a shell.
   *
   * @param owner the owning shell
   * @return the dialog color, or the owner's background where dialogs keep native colors
   */
  Color background(Shell owner);

  /**
   * Creates a check box readable on its page.
   *
   * @param parent the parent
   * @return the check box
   */
  Button check(Composite parent);
}
