package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.theme.ThemeController;
import io.github.ajdailey.qcaret.app.theme.ThemeListener;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.theme.ThemeService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
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
 */
public final class SwtThemeService implements ThemeService {
  private static final boolean GTK = "gtk".equals(SWT.getPlatform());
  private static final boolean COCOA = "cocoa".equals(SWT.getPlatform());
  private static final boolean WIN32 = "win32".equals(SWT.getPlatform());

  /** Display data key: true when controls start with the dark native theme. */
  private static final String NATIVE_DARK = SwtThemeService.class.getName() + ".nativeDark";

  /** Milliseconds between reads of the system setting on cocoa. */
  private static final int POLL_MILLIS = 1000;

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
            if (GTK) {
              for (Shell shell : display.getShells()) {
                recolorLinks(shell);
              }
            }
          }
        });
    if (COCOA) {
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
    if (mode == ThemeMode.SYSTEM || COCOA) {
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
   * Creates a check box readable on a light page. win32's dark native theme draws a check box label
   * near-white, or light gray when disabled, and dialog pages stay light; {@code setForeground}
   * reaches only an enabled box. The theme applies to a control when it is created, so under a dark
   * startup setting the box is created with the theme off.
   *
   * @param parent the parent
   * @return the check box
   */
  static Button check(Composite parent) {
    Display display = parent.getDisplay();
    boolean light = WIN32 && Boolean.TRUE.equals(display.getData(NATIVE_DARK));
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
      link.setLinkForeground(link.getDisplay().getSystemColor(SWT.COLOR_LINK_FOREGROUND));
    } else if (control instanceof Composite composite) {
      for (Control child : composite.getChildren()) {
        recolorLinks(child);
      }
    }
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
