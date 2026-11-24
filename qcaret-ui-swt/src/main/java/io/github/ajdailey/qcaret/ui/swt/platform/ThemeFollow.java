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
 *       <td>read by controls at creation; a change rebuilds the main windows</td><td>polled</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaThemeFollow}</td>
 *       <td>pinned to the resolved side; {@link CocoaChrome} recolors shells</td><td>polled</td></tr>
 *   <tr><td>GTK</td><td>{@link GtkThemeFollow}</td><td>switched live; links recolored</td>
 *       <td>{@code SWT.Settings}</td></tr>
 * </table>
 */
public interface ThemeFollow {
  /** Data key on a table: true when cocoa gave its header the colors of dark. */
  String HEADER_DARK = ThemeFollow.class.getName() + ".headerDark";

  /**
   * Sets the native controls' startup theme for a mode through {@link NativeTheme#set}: for light
   * and dark on GTK, and for every mode on win32 and cocoa, system from the system setting. GTK's
   * controls follow the GTK theme under system. Call before the first Shell exists.
   *
   * @param mode the theme setting
   * @return the startup setting: true when the dark native theme was set
   */
  boolean start(ThemeMode mode);

  /**
   * Starts following the system setting. win32 and cocoa run {@code refresh} every 500 ms while the
   * Display lives: cocoa SWT sends no {@code SWT.Settings} for an appearance change, and win32 SWT
   * sends it 2 s after {@code WM_SETTINGCHANGE}. cocoa also starts {@link CocoaChrome} and applies
   * the startup side. GTK reads the setting once, when the Display is created.
   *
   * @param dark the system setting now, the appearance cocoa SWT read its colors under
   * @param timers runs the poll's timer
   * @param refresh reads the system setting and reports a change
   */
  void watch(boolean dark, UiDispatcher timers, Runnable refresh);

  /**
   * Takes a changed system setting; every platform ignores it. Under system the theme controller
   * passes the new side to {@link #preferDark} and {@link #showOnWindows}.
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
   * Shows a palette side on the windows' native controls, after {@link #preferDark}, at every
   * change of the side the theme resolves to. On win32, when the side differs from {@link
   * DialogColors#NATIVE_DARK}, it sets the native theme and rebuilds the main windows; on cocoa it
   * pins the appearance and {@link CocoaChrome} recolors every shell.
   *
   * @param dark true for dark
   * @param windows rebuilds the main windows
   */
  void showOnWindows(boolean dark, WindowRebuild windows);

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
