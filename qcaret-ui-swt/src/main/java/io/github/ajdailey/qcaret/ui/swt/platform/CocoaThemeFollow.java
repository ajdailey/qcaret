package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Link;

/**
 * The theme on cocoa: native controls always follow the system appearance, and QCaret never sets
 * it. cocoa SWT reads its widget colors once, under the system appearance, and an application
 * appearance that differs draws text in its own appearance over those backgrounds, white on light
 * gray or black on dark gray. Do not pin the cocoa appearance. A restart changes nothing, so every
 * side shows now.
 */
final class CocoaThemeFollow implements ThemeFollow {
  private final Display display;
  private CocoaChrome chrome;

  CocoaThemeFollow(Display display) {
    this.display = display;
  }

  @Override
  public boolean start(ThemeMode mode) {
    return false;
  }

  @Override
  public void watch(boolean dark, UiDispatcher timers, Runnable refresh) {
    chrome = new CocoaChrome(display, dark);
    NativeTheme.poll(display, timers, refresh);
  }

  @Override
  public void systemChanged(boolean dark) {
    if (chrome != null) {
      chrome.changed(dark);
    }
  }

  @Override
  public void preferDark(boolean dark) {}

  @Override
  public boolean showsNow(boolean dark, boolean nativeDark) {
    return true;
  }

  @Override
  public void settingsChanged() {}

  @Override
  public void colorLink(Link link) {}
}
