package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;

/**
 * The system's light or dark setting from {@link Display#isSystemDarkTheme}, with a change event
 * from {@code SWT.Settings}. On GTK the setting is read once when the Display is created, so a
 * change made while QCaret runs applies at the next start; win32 and cocoa read it per call.
 *
 * <p>Native controls take the theme from {@link Display#setDarkThemePreferred}. GTK applies a
 * change at once, so {@link #preferDark} calls it there. win32 controls read it only when created,
 * so {@link #open} sets it before the first Shell and {@link #showsNow} reports whether that
 * startup setting matches a request. On cocoa native controls always follow the system appearance
 * and the service never calls it: cocoa SWT reads its widget colors once, under the system
 * appearance, and an application appearance that differs draws text in its own appearance over
 * those backgrounds, white on light gray or black on dark gray. Do not pin the cocoa appearance.
 */
public final class SwtThemeService implements ThemeService {
  private static final boolean GTK = "gtk".equals(SWT.getPlatform());
  private static final boolean COCOA = "cocoa".equals(SWT.getPlatform());

  private final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
  private final Display display;
  private boolean dark;
  private final boolean nativeDark;

  /**
   * Creates the service with native controls following the system; call on the UI thread.
   *
   * @param display the Display
   */
  public SwtThemeService(Display display) {
    this(display, false);
  }

  private SwtThemeService(Display display, boolean nativeDark) {
    this.display = display;
    this.nativeDark = nativeDark;
    dark = Display.isSystemDarkTheme();
    display.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            boolean now = Display.isSystemDarkTheme();
            if (now != dark) {
              dark = now;
              for (ThemeListener listener : new ArrayList<ThemeListener>(listeners)) {
                listener.systemThemeChanged(now);
              }
            }
          }
        });
  }

  /**
   * Creates the service and, for light or dark off cocoa, sets the native controls' theme; call
   * before the first Shell exists.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @return the service
   */
  public static SwtThemeService open(Display display, ThemeMode mode) {
    if (mode == ThemeMode.SYSTEM || COCOA) {
      return new SwtThemeService(display);
    }
    boolean value = ThemeController.resolve(mode, Display.isSystemDarkTheme()).dark();
    display.setDarkThemePreferred(value);
    return new SwtThemeService(display, value);
  }

  @Override
  public boolean systemDark() {
    return dark;
  }

  @Override
  public void addListener(ThemeListener listener) {
    listeners.add(listener);
  }

  @Override
  public void preferDark(boolean value) {
    if (GTK && !display.isDisposed()) {
      display.setDarkThemePreferred(value);
    }
  }

  @Override
  public boolean showsNow(boolean value) {
    // cocoa controls follow the system at every start, so a restart changes nothing.
    return GTK || COCOA || value == nativeDark;
  }
}
