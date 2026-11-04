package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.about.AboutInfo;

/**
 * The main window as the controllers see it. Every method is called on the UI thread and returns
 * without waiting for the user.
 */
public interface MainView extends ClipboardService {
  /** Hides the window while shutdown completes. */
  void hide();

  /** Disposes the window, which ends the event loop. */
  void dispose();

  /**
   * Shows a message without waiting for the user to dismiss it.
   *
   * @param title the window title
   * @param message one line
   */
  void showInfo(String title, String message);

  /**
   * Shows read-only text with a Copy button.
   *
   * @param title the window title
   * @param text the text
   */
  void showText(String title, String text);

  /**
   * Shows the About dialog.
   *
   * @param info what it shows
   */
  void showAbout(AboutInfo info);

  /**
   * Describes the UI toolkit for About.
   *
   * @return version and platform, for example {@code SWT 4971 gtk}
   */
  String toolkitDescription();

  /**
   * Returns the window's normal (unmaximized) position and client-area size.
   *
   * @return the bounds; null fields are unknown
   */
  WindowGeometry.Request windowBounds();

  /**
   * Reports whether the window is maximized.
   *
   * @return true when maximized
   */
  boolean windowMaximized();
}
