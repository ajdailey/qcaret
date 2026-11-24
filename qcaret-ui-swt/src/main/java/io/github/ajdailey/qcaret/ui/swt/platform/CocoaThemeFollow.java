package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;

/**
 * The theme on cocoa: {@code setDarkThemePreferred} pins the application appearance to the side the
 * theme resolves to, at start and at each change, system included, which re-pins on each change the
 * poll reads. SWT has no call that unpins it. cocoa SWT reads its widget colors once, under the
 * system appearance at Display creation, while AppKit draws native text in the pinned appearance,
 * so pin only together with {@link CocoaChrome}, which recolors every shell from the pinned side: a
 * pin alone draws white text on light gray or black on dark gray.
 */
final class CocoaThemeFollow implements ThemeFollow {
  private final Display display;
  private CocoaChrome chrome;
  private boolean started;
  private boolean pinned;

  CocoaThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    started = true;
    pinned =
        NativeTheme.set(display, ThemeController.resolve(mode, Display.isSystemDarkTheme()).dark());
    return pinned;
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {
    chrome = new CocoaChrome(display, dark);
    chrome.apply(started ? pinned : dark);
    NativeTheme.poll(display, timers, refresh);
  }

  @Override
  public void systemChanged(boolean dark) {}

  @Override
  public void preferDark(boolean dark) {}

  @Override
  public void showOnWindows(boolean dark, WindowRebuild windows) {
    started = true;
    pinned = NativeTheme.set(display, dark);
    if (chrome != null) {
      chrome.apply(dark);
    }
  }

  @Override
  public void settingsChanged() {}

  @Override
  public void colorLink(Link link) {}
}
