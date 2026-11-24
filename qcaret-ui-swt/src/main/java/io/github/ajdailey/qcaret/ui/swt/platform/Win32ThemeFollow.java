package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;

/**
 * The theme on win32: native controls read {@code setDarkThemePreferred} only when created, so it
 * is set once before the first Shell, under the system theme from the system setting at start.
 */
final class Win32ThemeFollow implements ThemeFollow {
  private final Display display;

  Win32ThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    return NativeTheme.set(
        display, ThemeController.resolve(mode, Display.isSystemDarkTheme()).dark());
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {
    NativeTheme.poll(display, timers, refresh);
  }

  @Override
  public void systemChanged(boolean dark) {}

  @Override
  public void preferDark(boolean dark) {}

  @Override
  public boolean showsNow(boolean dark, boolean nativeDark) {
    return dark == nativeDark;
  }

  @Override
  public void settingsChanged() {}

  @Override
  public void colorLink(Link link) {}
}
