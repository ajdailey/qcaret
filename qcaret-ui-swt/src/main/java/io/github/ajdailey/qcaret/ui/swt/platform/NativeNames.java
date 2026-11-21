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
 *   <tr><td>GTK 4, win32, cocoa</td><td>{@link #NONE}</td></tr>
 * </table>
 */
public interface NativeNames {
  /** Sets nothing. */
  NativeNames NONE =
      new NativeNames() {
        @Override
        public void name(ToolItem item, String name) {
          // Nothing to set.
        }

        @Override
        public void name(Control control, String name) {
          // Nothing to set.
        }
      };

  /** This platform's names, chosen on first use. */
  NativeNames PLATFORM = forPlatform();

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

  /**
   * Returns the implementation for the running SWT.
   *
   * @return {@link Gtk3NativeNames} where {@link PlatformTraits#namesThroughAtk} holds, otherwise
   *     {@link #NONE}
   */
  static NativeNames forPlatform() {
    return PlatformTraits.PLATFORM.namesThroughAtk() ? new Gtk3NativeNames() : NONE;
  }
}
