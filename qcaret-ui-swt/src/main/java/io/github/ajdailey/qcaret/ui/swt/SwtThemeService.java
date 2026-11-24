package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qcaret.ui.swt.platform.Contrast;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogSurfaces;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * The system's light or dark setting from {@link Display#isSystemDarkTheme}, with a change event
 * from {@code SWT.Settings}. On GTK the setting is read once when the Display is created, so a
 * change made while QCaret runs applies at the next start; win32 and cocoa read it per call and the
 * service polls there: cocoa SWT sends no {@code SWT.Settings} for an appearance change, and win32
 * SWT sends it 2 s after the system's. A change applies once, from whichever comes first. On cocoa
 * {@link CocoaChrome} recolors the shells.
 *
 * <p>Native controls take the theme from {@link Display#setDarkThemePreferred}. GTK applies a
 * change at once, so {@link #preferDark} calls it there. win32 controls read it only when created,
 * so {@link #open} sets it before the first Shell, under the system theme from the system setting
 * at start, and {@link #showsNow} reports whether that startup setting matches a request. On cocoa
 * native controls always follow the system appearance and the service never calls it: cocoa SWT
 * reads its widget colors once, under the system appearance, and an application appearance that
 * differs draws text in its own appearance over those backgrounds, white on light gray or black on
 * dark gray. Do not pin the cocoa appearance.
 *
 * <p>A GTK theme with no dark variant ignores {@code setDarkThemePreferred}, and so does a named
 * theme that is not installed: GTK falls back to light Adwaita. Native controls then stay light
 * under the dark palette.
 */
public final class SwtThemeService implements ThemeService {
  private static final PlatformTraits PLATFORM = PlatformTraits.PLATFORM;

  /** Milliseconds between reads of the system setting on win32 and cocoa. */
  private static final int POLL_MILLIS = 500;

  private static final BooleanSupplier SYSTEM_SETTING =
      new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
          return Display.isSystemDarkTheme();
        }
      };

  private final List<ThemeListener> listeners = new ArrayList<ThemeListener>();
  private final Display display;
  private final BooleanSupplier system;
  private boolean dark;
  private final boolean nativeDark;
  private CocoaChrome chrome;

  /**
   * Creates the service with native controls following the system; call on the UI thread.
   *
   * @param display the Display
   */
  public SwtThemeService(Display display) {
    this(display, false, SYSTEM_SETTING, PLATFORM.pollsSystemTheme());
  }

  /**
   * Creates the service over a given setting.
   *
   * @param display the Display
   * @param nativeDark the startup setting of native controls
   * @param system reads the system setting
   * @param poll whether to read the setting every {@code POLL_MILLIS}
   */
  SwtThemeService(Display display, boolean nativeDark, BooleanSupplier system, boolean poll) {
    this.display = display;
    this.nativeDark = nativeDark;
    this.system = system;
    dark = system.getAsBoolean();
    display.addListener(
        SWT.Settings,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            refresh();
            if (PLATFORM.raisesLinkContrast()) {
              for (Shell shell : display.getShells()) {
                recolorLinks(shell);
              }
            }
          }
        });
    if (PLATFORM.followsSystemAppearance()) {
      chrome = new CocoaChrome(display, dark);
    }
    if (poll) {
      final SwtUiDispatcher timers = new SwtUiDispatcher(display);
      timers.timerExec(
          POLL_MILLIS,
          new Runnable() {
            @Override
            public void run() {
              if (!display.isDisposed()) {
                refresh();
                timers.timerExec(POLL_MILLIS, this);
              }
            }
          });
    }
  }

  /** Reads the system setting and reports a change. */
  void refresh() {
    boolean now = system.getAsBoolean();
    if (now != dark) {
      dark = now;
      if (chrome != null) {
        chrome.apply(now);
      }
      for (ThemeListener listener : new ArrayList<ThemeListener>(listeners)) {
        listener.systemThemeChanged(now);
      }
    }
  }

  /**
   * Creates the service and sets the native controls' theme to the mode's palette side: for light
   * or dark on GTK and win32, and for system on win32, where the system setting is read once here.
   * On cocoa, and for system on GTK, native controls follow the platform's own setting. Call before
   * the first Shell exists.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @return the service
   */
  public static SwtThemeService open(Display display, ThemeMode mode) {
    return open(display, mode, PLATFORM, SYSTEM_SETTING);
  }

  /**
   * Creates the service as {@link #open(Display, ThemeMode)} does, for given traits and system
   * setting.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @param platform the platform's traits
   * @param system reads the system setting
   * @return the service
   */
  static SwtThemeService open(
      Display display, ThemeMode mode, PlatformTraits platform, BooleanSupplier system) {
    if (platform.followsSystemAppearance()
        || (mode == ThemeMode.SYSTEM && !platform.themesAtCreation())) {
      return new SwtThemeService(display, false, system, platform.pollsSystemTheme());
    }
    boolean value = ThemeController.resolve(mode, system.getAsBoolean()).dark();
    display.setDarkThemePreferred(value);
    display.setData(ChromeColors.NATIVE_DARK, value);
    return new SwtThemeService(display, value, system, platform.pollsSystemTheme());
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
   * Creates a check box readable on its page ({@link DialogSurfaces#check}).
   *
   * @param parent the parent
   * @return the check box
   */
  static Button check(Composite parent) {
    return DialogSurfaces.platform().check(parent);
  }

  /**
   * Gives each link the current link color. GTK SWT refreshes its system colors and repaints on the
   * first {@link Display#sleep} after a theme change, before {@code SWT.Settings}, but a {@link
   * Link} keeps the color of its creation: 2.2 contrast on dark.
   */
  private static void recolorLinks(Control control) {
    if (control instanceof Link link) {
      colorLink(link);
    } else if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        recolorLinks(child);
      }
    }
  }

  /**
   * Gives a link the theme's link color, made readable on its background; call when creating a
   * link. GTK only: Adwaita's dark link color has 3.25 contrast on its own window background.
   *
   * @param link the link
   */
  static void colorLink(Link link) {
    if (PLATFORM.raisesLinkContrast()) {
      RGB theme = link.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND).getRGB();
      link.setLinkForeground(new Color(readable(theme, link.getBackground().getRGB())));
    }
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
    if (PLATFORM.switchesThemeLive() && !display.isDisposed()) {
      display.setDarkThemePreferred(value);
    }
  }

  @Override
  public boolean showsNow(boolean value) {
    // cocoa controls follow the system at every start, so a restart changes nothing.
    return PLATFORM.switchesThemeLive()
        || PLATFORM.followsSystemAppearance()
        || value == nativeDark;
  }
}
