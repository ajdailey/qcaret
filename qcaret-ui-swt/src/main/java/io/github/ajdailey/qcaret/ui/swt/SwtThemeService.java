package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
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
 * change made while QCaret runs applies at the next start; win32 and cocoa read it per call. cocoa
 * SWT sends no {@code SWT.Settings} for an appearance change, so the service polls there and {@link
 * CocoaChrome} recolors the shells.
 *
 * <p>Native controls take the theme from {@link Display#setDarkThemePreferred}. GTK applies a
 * change at once, so {@link #preferDark} calls it there. win32 controls read it only when created,
 * so {@link #open} sets it before the first Shell and {@link #showsNow} reports whether that
 * startup setting matches a request. On cocoa native controls always follow the system appearance
 * and the service never calls it: cocoa SWT reads its widget colors once, under the system
 * appearance, and an application appearance that differs draws text in its own appearance over
 * those backgrounds, white on light gray or black on dark gray. Do not pin the cocoa appearance.
 *
 * <p>A GTK theme with no dark variant ignores {@code setDarkThemePreferred}, and so does a named
 * theme that is not installed: GTK falls back to light Adwaita. Native controls then stay light
 * under the dark palette.
 */
public final class SwtThemeService implements ThemeService {
  private static final PlatformTraits PLATFORM = PlatformTraits.PLATFORM;

  /** Display data key: true when controls start with the dark native theme. */
  private static final String NATIVE_DARK = SwtThemeService.class.getName() + ".nativeDark";

  /** Milliseconds between reads of the system setting on cocoa. */
  private static final int POLL_MILLIS = 1000;

  /** Lowest contrast of link text on its background: the WCAG AA ratio for normal text. */
  private static final double LINK_CONTRAST = 4.5;

  /** Percent of the way to white or black each step moves a link color. */
  private static final int LINK_STEP_PERCENT = 5;

  private static final RGB WHITE = new RGB(255, 255, 255);
  private static final RGB BLACK = new RGB(0, 0, 0);

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
    this(display, false, SYSTEM_SETTING);
  }

  /**
   * Creates the service over a given setting.
   *
   * @param display the Display
   * @param nativeDark the startup setting of native controls
   * @param system reads the system setting
   */
  SwtThemeService(Display display, boolean nativeDark, BooleanSupplier system) {
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
   * Creates the service and, for light or dark off cocoa, sets the native controls' theme; call
   * before the first Shell exists.
   *
   * @param display a Display with no Shell
   * @param mode the theme setting
   * @return the service
   */
  public static SwtThemeService open(Display display, ThemeMode mode) {
    if (mode == ThemeMode.SYSTEM || PLATFORM.followsSystemAppearance()) {
      return new SwtThemeService(display);
    }
    boolean value = ThemeController.resolve(mode, Display.isSystemDarkTheme()).dark();
    display.setDarkThemePreferred(value);
    display.setData(NATIVE_DARK, value);
    return new SwtThemeService(display, value, SYSTEM_SETTING);
  }

  /**
   * Reports the startup setting of native controls.
   *
   * @param display the Display
   * @return true when {@link #open} turned the dark native theme on
   */
  static boolean nativeDark(Display display) {
    return Boolean.TRUE.equals(display.getData(NATIVE_DARK));
  }

  /**
   * Creates a check box readable on its page. win32's dark native theme draws a check box label
   * near-white, or light gray when disabled, and the main window's docks and bars stay light;
   * {@code setForeground} reaches only an enabled box. The theme applies to a control when it is
   * created, so under a dark startup setting a box outside a {@link DialogSurfaces} dialog is
   * created with the theme off.
   *
   * @param parent the parent
   * @return the check box
   */
  static Button check(Composite parent) {
    Display display = parent.getDisplay();
    boolean light =
        PLATFORM.lightChecksUnderDark()
            && Boolean.TRUE.equals(display.getData(NATIVE_DARK))
            && !DialogSurfaces.platform().colored(parent);
    if (light) {
      display.setDarkThemePreferred(false);
    }
    try {
      return new Button(parent, SWT.CHECK);
    } finally {
      if (light) {
        display.setDarkThemePreferred(true);
      }
    }
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
   * Moves a text color toward white or black, whichever contrasts more with the ground, until it
   * reaches the link contrast.
   *
   * @param text the text color
   * @param ground the background color
   * @return the text color when already readable, otherwise the first readable step
   */
  static RGB readable(RGB text, RGB ground) {
    RGB target = contrast(WHITE, ground) >= contrast(BLACK, ground) ? WHITE : BLACK;
    RGB result = text;
    for (int percent = LINK_STEP_PERCENT;
        percent <= 100 && contrast(result, ground) < LINK_CONTRAST;
        percent += LINK_STEP_PERCENT) {
      result =
          new RGB(
              mix(text.red, target.red, percent),
              mix(text.green, target.green, percent),
              mix(text.blue, target.blue, percent));
    }
    return result;
  }

  private static int mix(int from, int to, int percent) {
    return from + (to - from) * percent / 100;
  }

  /**
   * Returns the WCAG contrast ratio of two colors.
   *
   * @param first a color
   * @param second the other color
   * @return the ratio, from 1 to 21
   */
  static double contrast(RGB first, RGB second) {
    double a = luminance(first);
    double b = luminance(second);
    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
  }

  private static double luminance(RGB rgb) {
    return 0.2126 * linear(rgb.red) + 0.7152 * linear(rgb.green) + 0.0722 * linear(rgb.blue);
  }

  private static double linear(int channel) {
    double value = channel / 255.0;
    return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
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
