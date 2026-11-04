package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import java.nio.file.Path;

/**
 * The main window as the controllers see it. Every method but the {@link ActivityIndicator} ones is
 * called on the UI thread and returns without waiting for the user. The activity count drives the
 * busy cursor.
 */
public interface MainView extends ClipboardService, DocumentsView, ActivityIndicator {
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

  /**
   * Shows a message in the status bar for a few seconds.
   *
   * @param message one line
   */
  void showNotice(String message);

  /**
   * Shows the recent log lines in the log window, opening it or replacing its text.
   *
   * @param text the lines
   */
  void showLog(String text);

  /**
   * Shows or hides the status-bar marker for warnings logged since Show Log last opened.
   *
   * @param shown true to show it
   */
  void showWarningMarker(boolean shown);

  /**
   * Opens a file or folder in the application the system associates with it.
   *
   * @param target the file or folder
   * @return false when no application took it
   */
  boolean launch(Path target);

  /**
   * Opens a web address in the system browser.
   *
   * @param url the address
   * @return false when no application took it
   */
  boolean launchUrl(String url);

  /**
   * Sets the editor font size in every editor, in place.
   *
   * @param step points above (positive) or below the default size
   */
  void setFontStep(int step);
}
