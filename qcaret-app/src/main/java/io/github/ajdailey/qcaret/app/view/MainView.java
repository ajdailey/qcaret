package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.Keymap;
import java.nio.file.Path;

/**
 * The main window as the controllers see it. Every method but the {@link ActivityIndicator} ones is
 * called on the UI thread and returns without waiting for the user. The activity count drives the
 * busy cursor.
 */
public interface MainView
    extends ClipboardService,
        DocumentsView,
        ActivityIndicator,
        SearchView,
        BookmarksView,
        WorkspaceView,
        CompareView,
        SessionView {
  /** Hides the window while shutdown completes. */
  void hide();

  /** Restores the window when minimized and brings it to the front. */
  void toFront();

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
   * Shows the user guide in a window with an embedded browser, or brings that window to the front.
   * When the browser fails or the page does not load in time after this returned true, the view
   * closes the window and calls {@code ApplicationController.showGuideElsewhere}.
   *
   * @param file the guide's HTML file
   * @return false when the browser fails before the window opens
   */
  boolean showGuide(Path file);

  /**
   * Sets the editor font size.
   *
   * @param points the size, 0 for the platform's default
   */
  void setFontSize(int points);

  /**
   * Returns the editor font's platform default size.
   *
   * @return the size in points
   */
  int defaultFontSize();

  /**
   * Shows the Keyboard Shortcuts window with a filter, opening it or bringing it to the front.
   *
   * @param keymap the bindings it lists
   */
  void showKeyboardShortcuts(Keymap keymap);

  /**
   * Shows the command palette: a filter field over the rows {@link PaletteInput#filter} returns.
   * Choosing a row closes the palette and passes the row's command to {@link PaletteInput#run}.
   *
   * @param input the palette's controller
   */
  void showCommandPalette(PaletteInput input);

  /**
   * Shows the Preferences dialog, or raises it when it shows.
   *
   * @param input the dialog's controller
   */
  void showPreferences(PreferencesInput input);

  /**
   * Rebuilds the menus and the editors' keys from a keymap.
   *
   * @param keymap the bindings
   */
  void setKeymap(Keymap keymap);
}
