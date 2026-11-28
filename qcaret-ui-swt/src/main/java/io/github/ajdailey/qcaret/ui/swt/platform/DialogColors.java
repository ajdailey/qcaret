package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;

/**
 * Dialog colors where a platform's native theme leaves a dialog body unmatched to its title bar and
 * buttons, and check boxes readable on their page. One class per platform; {@link
 * PlatformTraits#dialogColors} picks it.
 */
public interface DialogColors {
  /**
   * Display data key: {@code Boolean.TRUE} while native controls are set to the dark theme, from
   * the start and after each live switch on win32 and cocoa.
   */
  String NATIVE_DARK = DialogColors.class.getName() + ".nativeDark";

  /**
   * Reports the native controls' setting, as {@link #NATIVE_DARK} records it.
   *
   * @param display the Display
   * @return true while the dark native theme is set
   */
  static boolean nativeDark(Display display) {
    return Boolean.TRUE.equals(display.getData(NATIVE_DARK));
  }

  /**
   * Returns the running platform's implementation.
   *
   * @return {@link PlatformTraits#dialogColors} of {@link PlatformTraits#PLATFORM}
   */
  static DialogColors platform() {
    return PlatformTraits.PLATFORM.dialogColors();
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
   * Shows a dialog table's grid lines. Use in place of {@link Table#setLinesVisible}: win32 draws
   * native grid lines in the light {@code COLOR_3DFACE} under the dark native theme, so {@link
   * Win32DialogColors} hides them in a colored dialog and draws its own.
   *
   * @param table a table in a dialog shell
   */
  void gridLines(Table table);
}
