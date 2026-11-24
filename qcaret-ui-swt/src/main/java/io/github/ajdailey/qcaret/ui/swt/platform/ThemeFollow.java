package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Link;

/**
 * How native controls follow the light or dark theme on one Display, one implementation per
 * platform from {@link PlatformTraits#themeFollow}. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Native theme</th><th>System setting</th></tr>
 *   <tr><td>win32</td><td>{@link Win32ThemeFollow}</td>
 *       <td>set before the first Shell, read by controls at creation</td><td>polled</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaThemeFollow}</td>
 *       <td>the system appearance; {@link CocoaChrome} recolors shells</td><td>polled</td></tr>
 *   <tr><td>GTK</td><td>{@link GtkThemeFollow}</td><td>switched live; links recolored</td>
 *       <td>{@code SWT.Settings}</td></tr>
 * </table>
 */
public interface ThemeFollow {
  /** Data key on a table: true when cocoa gave its header the colors of dark. */
  String HEADER_DARK = ThemeFollow.class.getName() + ".headerDark";

  /**
   * Sets the native controls' startup theme for a mode through {@link NativeTheme#set}: for light
   * and dark on GTK and win32, and for system on win32 from the system setting. cocoa controls
   * always follow the system appearance, and GTK's do for system. Call before the first Shell
   * exists.
   *
   * @param mode the theme setting
   * @return the startup setting: true when the dark native theme was set
   */
  boolean start(ThemeMode mode);

  /**
   * Starts following the system setting. win32 and cocoa run {@code refresh} every 500 ms while the
   * Display lives: cocoa SWT sends no {@code SWT.Settings} for an appearance change, and win32 SWT
   * sends it 2 s after {@code WM_SETTINGCHANGE}. cocoa also starts {@link CocoaChrome}. GTK reads
   * the setting once, when the Display is created.
   *
   * @param dark the system setting now
   * @param timers runs the poll's timer
   * @param refresh reads the system setting and reports a change
   */
  void watch(boolean dark, UiDispatcher timers, Runnable refresh);

  /**
   * Applies a changed system setting: on cocoa {@link CocoaChrome} recolors every shell.
   *
   * @param dark true for dark
   */
  void systemChanged(boolean dark);

  /**
   * Applies a palette side to native controls at once where the platform can: on GTK.
   *
   * @param dark true for dark
   */
  void preferDark(boolean dark);

  /**
   * Reports whether native controls show a side without a restart. win32 controls read the theme
   * only when created, so there only the startup side shows.
   *
   * @param dark the side
   * @param nativeDark the startup setting
   * @return true when the side shows now
   */
  boolean showsNow(boolean dark, boolean nativeDark);

  /**
   * Takes an {@code SWT.Settings} event. GTK refreshes its system colors at a theme change, but a
   * {@link Link} keeps the color of its creation, so on GTK every link gets the new link color.
   */
  void settingsChanged();

  /**
   * Gives a link the theme's link color raised to readable contrast on its background, on GTK:
   * Adwaita's dark link color has 3.25 contrast on its own window background. Call when creating a
   * link.
   *
   * @param link the link
   */
  void colorLink(Link link);
}
