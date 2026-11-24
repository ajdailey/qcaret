package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolItem;

/**
 * Sets the name a screen reader reads for a native widget that SWT names from no Java accessibility
 * listener: a tool item, or a control the toolkit draws, such as a {@code Tree}. SWT on GTK 3 calls
 * those listeners only for the controls it draws itself. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th></tr>
 *   <tr><td>GTK 3</td><td>{@link Gtk3NativeNames}</td></tr>
 *   <tr><td>GTK 4, win32, cocoa</td><td>{@link NoNativeNames}</td></tr>
 * </table>
 */
public interface NativeNames {
  /** This platform's names, chosen on first use by {@link PlatformTraits#nativeNames}. */
  NativeNames PLATFORM = PlatformTraits.PLATFORM.nativeNames();

  /**
   * Names a tool item. A failure logs one WARN and leaves the item unnamed.
   *
   * @param item the item
   * @param name the name
   */
  void name(ToolItem item, String name);

  /**
   * Names a control the toolkit draws. A failure logs one WARN and leaves the control unnamed.
   *
   * @param control the control
   * @param name the name
   */
  void name(Control control, String name);
}
