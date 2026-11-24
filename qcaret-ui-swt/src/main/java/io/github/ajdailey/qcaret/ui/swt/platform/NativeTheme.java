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
   * win32 and cocoa.
   *
   * @param display the Display
   * @param dark true for the dark native theme
   * @return {@code dark}
   */
  static boolean set(Display display, boolean dark) {
    display.setDarkThemePreferred(dark);
    display.setData(DialogColors.NATIVE_DARK, dark);
    return dark;
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
