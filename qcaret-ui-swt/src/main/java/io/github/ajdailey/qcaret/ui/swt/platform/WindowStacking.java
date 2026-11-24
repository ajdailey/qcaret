package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;

/**
 * Keeps a shell above other windows (View > Always on Top). SWT has no public call for it, so each
 * supported platform has a class that reaches SWT internals by reflection, and the product compiles
 * against every platform's SWT jar. Confined to the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th></tr>
 *   <tr><td>win32</td><td>{@link Win32Stacking}</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaStacking}</td></tr>
 *   <tr><td>GTK 3 on X11</td><td>{@link GtkX11Stacking}</td></tr>
 *   <tr><td>GTK 3 on Wayland, GTK 4</td><td>{@link WindowManagerStacking}</td></tr>
 * </table>
 */
public interface WindowStacking {
  /**
   * This platform's stacking, chosen on first use by {@link PlatformTraits#stacking}. GTK reads the
   * backend the Display records, so the first use follows the Display's creation.
   */
  WindowStacking PLATFORM = PlatformTraits.PLATFORM.stacking();

  /**
   * Reports whether {@link #setOnTop} takes effect; false after a reflective call failed.
   *
   * @return true when supported
   */
  boolean supported();

  /**
   * Keeps the shell above other windows, or returns it to normal stacking, without moving, resizing
   * or activating it. A failure logs one WARN and makes the stacking unsupported.
   *
   * @param shell a main window's shell
   * @param onTop true to keep it above
   */
  void setOnTop(Shell shell, boolean onTop);

  /**
   * Explains on the disabled Always on Top item that the platform cannot keep a window on top: on
   * GTK, the platform whose stacking can be unsupported. Elsewhere does nothing.
   *
   * @param item the menu item
   */
  void explainUnsupported(MenuItem item);
}
