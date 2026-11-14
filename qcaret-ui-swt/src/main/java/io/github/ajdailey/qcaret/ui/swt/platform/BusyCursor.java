package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.widgets.Display;

/**
 * The pointer over a busy window or editor: an arrow with a busy mark. One class per SWT platform
 * names the system cursor; {@link #PLATFORM} is the running one.
 *
 * <table>
 *   <caption>Cursor per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>SWT cursor</th><th>Native cursor</th></tr>
 *   <tr><td>win32</td><td>{@link Win32BusyCursor}</td><td>{@code CURSOR_APPSTARTING}</td>
 *       <td>{@code IDC_APPSTARTING}</td></tr>
 *   <tr><td>GTK</td><td>{@link GtkBusyCursor}</td><td>{@code CURSOR_APPSTARTING}</td>
 *       <td>{@code left_ptr_watch} on GTK 3, {@code progress} on GTK 4</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaBusyCursor}</td><td>{@code CURSOR_WAIT}</td>
 *       <td>{@code busyButClickableCursor}</td></tr>
 * </table>
 */
public interface BusyCursor {
  /** The running platform's busy cursor. */
  BusyCursor PLATFORM = of(SWT.getPlatform());

  /**
   * Returns a platform's busy cursor.
   *
   * @param platform an {@link SWT#getPlatform} value
   * @return the busy cursor
   * @throws IllegalArgumentException for a platform SWT does not build
   */
  static BusyCursor of(String platform) {
    return switch (platform) {
      case "win32" -> new Win32BusyCursor();
      case "cocoa" -> new CocoaBusyCursor();
      case "gtk" -> new GtkBusyCursor();
      default -> throw new IllegalArgumentException(platform);
    };
  }

  /**
   * Returns the SWT system cursor.
   *
   * @return an {@code SWT.CURSOR_} constant
   */
  int style();

  /**
   * Returns the display's shared busy cursor; never dispose it.
   *
   * @param display the display
   * @return the cursor
   */
  default Cursor cursor(Display display) {
    return display.getSystemCursor(style());
  }
}
