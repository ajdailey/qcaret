package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qcaret.ui.swt.platform.Contrast;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogColors;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import io.github.ajdailey.qcaret.ui.swt.platform.ThemeFollow;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;

/**
 * The system's light or dark setting from {@link Display#isSystemDarkTheme}, with a change event
 * from {@code SWT.Settings}. On GTK the setting is read once when the Display is created, so a
 * change made while QCaret runs applies at the next start; win32 and cocoa read it per call and the
 * service polls there ({@link ThemeFollow#watch}). A change applies once, from whichever comes
 * first.
 *
 * <p>Native controls take the theme from {@link Display#setDarkThemePreferred}, as {@link
 * ThemeFollow} applies it per platform: {@link #open} sets the startup theme, {@link #preferDark}
 * switches it live on GTK, and {@link #showsNow} reports whether a side shows without a restart.
 *
 * <p>A GTK theme with no dark variant ignores {@code setDarkThemePreferred}, and so does a named
 * theme that is not installed: GTK falls back to light Adwaita. Native controls then stay light
 * under the dark palette.
 */
public final class SwtThemeService implements ThemeService {
  private static final BooleanSupplier SYSTEM_SETTING =
      new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
          return Display.isSystemDarkTheme();
        }
      };

  private final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
  private final BooleanSupplier system;
  private final ThemeFollow follow;
  private boolean dark;
  private final boolean nativeDark;

  /**
   * Creates the service with native controls following the system; call on the UI thread.
   *
   * @param display the Display
   */
  public SwtThemeService(Display display) {
    this(display, false, SYSTEM_SETTING, PlatformTraits.PLATFORM.themeFollow(display));
  }

  /**
   * Creates the service over a given setting.
   *
   * @param display the Display
   * @param nativeDark the startup setting of native controls
   * @param system reads the system setting
   * @param follow how native controls follow the theme
   */
  SwtThemeService(
      final Display display, boolean nativeDark, BooleanSupplier system, ThemeFollow follow) {
    this.nativeDark = nativeDark;
    this.system = system;
    this.follow = follow;
    dark = system.getAsBoolean();
    display.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            refresh();
            SwtThemeService.this.follow.settingsChanged();
          }
        });
    follow.watch(
        dark,
        new SwtUiDispatcher(display),
        new Runnable() {
          @Override
          public void run() {
            refresh();
          }
        });
  }

  /** Reads the system setting and reports a change. */
  void refresh() {
    boolean now = system.getAsBoolean();
    if (now != dark) {
      dark = now;
      follow.systemChanged(now);
      for (ThemeListener listener : new ArrayList<ThemeListener>(listeners)) {
        listener.systemThemeChanged(now);
      }
    }
  }

  /**
   * Creates the service and sets the native controls' startup theme for the mode ({@link
   * ThemeFollow#start}). Call before the first Shell exists.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @return the service
   */
  public static SwtThemeService open(Display display, ThemeMode mode) {
    return open(display, mode, PlatformTraits.PLATFORM.themeFollow(display));
  }

  /**
   * Creates the service as {@link #open(Display, ThemeMode)} does, with a given platform's theme
   * following.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @param follow the platform's theme following on the Display
   * @return the service
   */
  static SwtThemeService open(Display display, ThemeMode mode, ThemeFollow follow) {
    boolean value = follow.start(mode);
    return new SwtThemeService(display, value, SYSTEM_SETTING, follow);
  }

  /**
   * Reports the startup setting of native controls.
   *
   * @param display the Display
   * @return true when {@link #open} turned the dark native theme on
   */
  static boolean nativeDark(Display display) {
    return ChromeColors.nativeDark(display);
  }

  /**
   * Creates a check box readable on its page ({@link DialogColors#check}).
   *
   * @param parent the parent
   * @return the check box
   */
  static Button check(Composite parent) {
    return DialogColors.platform().check(parent);
  }

  /**
   * Gives a link the theme's link color where the platform raises it ({@link
   * ThemeFollow#colorLink}); call when creating a link.
   *
   * @param link the link
   */
  static void colorLink(Link link) {
    PlatformTraits.PLATFORM.themeFollow(link.getDisplay()).colorLink(link);
  }

  /**
   * Returns a text color raised to the link contrast on a ground ({@link Contrast#readable}).
   *
   * @param text the text color
   * @param ground the background color
   * @return the readable color
   */
  static RGB readable(RGB text, RGB ground) {
    return Contrast.readable(text, ground);
  }

  /**
   * Returns the WCAG contrast ratio of two colors ({@link Contrast#ratio}).
   *
   * @param first a color
   * @param second the other color
   * @return the ratio, from 1 to 21
   */
  static double contrast(RGB first, RGB second) {
    return Contrast.ratio(first, second);
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
    follow.preferDark(value);
  }

  @Override
  public boolean showsNow(boolean value) {
    return follow.showsNow(value, nativeDark);
  }
}
