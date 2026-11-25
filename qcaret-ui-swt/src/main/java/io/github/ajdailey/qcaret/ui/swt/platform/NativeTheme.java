package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import org.eclipse.swt.widgets.Display;

/**
 * The native theme's setting, the system setting and its poll. Only this package reads {@link
 * Display#isSystemDarkTheme}.
 */
public final class NativeTheme {
  /** Milliseconds between reads of the system setting on win32 and cocoa. */
  static final int POLL_MILLIS = 500;

  /** Display data key: the first side {@link #set} gave, kept through live switches. */
  private static final String START_DARK = NativeTheme.class.getName() + ".startDark";

  private NativeTheme() {}

  /**
   * Reads the system's light or dark setting.
   *
   * @return {@link Display#isSystemDarkTheme}
   */
  public static boolean systemDark() {
    return Display.isSystemDarkTheme();
  }

  /**
   * Sets the native theme and records it as {@link DialogColors#NATIVE_DARK}, which the win32
   * chrome and dialog colors read at each use: before the first Shell, and at a live switch on
   * win32 and cocoa. The first call also sets the side {@link #startDark} reports.
   *
   * @param display the Display
   * @param dark true for the dark native theme
   * @return {@code dark}
   */
  static boolean set(Display display, boolean dark) {
    display.setDarkThemePreferred(dark);
    display.setData(DialogColors.NATIVE_DARK, dark);
    if (display.getData(START_DARK) == null) {
      display.setData(START_DARK, dark);
    }
    return dark;
  }

  /**
   * Reports the native side the Display started with. win32 pop-up menus keep it after a live
   * switch.
   *
   * @param display the Display
   * @return the first side {@link #set} gave, or {@link DialogColors#nativeDark} before any
   */
  static boolean startDark(Display display) {
    Object start = display.getData(START_DARK);
    return start == null ? DialogColors.nativeDark(display) : Boolean.TRUE.equals(start);
  }

  /**
   * Runs a refresh every {@link #POLL_MILLIS} while the Display lives.
   *
   * @param display the Display
   * @param ui runs the timer
   * @param refresh reads the system setting
   */
  static void poll(final Display display, final UiDispatcher ui, final Runnable refresh) {
    ui.timerExec(
        POLL_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            if (!display.isDisposed()) {
              refresh.run();
              ui.timerExec(POLL_MILLIS, this);
            }
          }
        });
  }
}
