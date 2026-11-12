package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.about.AboutInfo;
import io.github.ajdailey.qcaret.app.command.Keymap;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

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
   * Sets the editor font family in every view that shows it.
   *
   * @param family the family; empty for the platform's monospace font
   */
  void setFontFamily(String family);

  /**
   * Shows or hides the main toolbar.
   *
   * @param shown true to show it
   */
  void setToolbarShown(boolean shown);

  /**
   * Shows or hides the line numbers in every editor of the window, later ones included.
   *
   * @param shown true to show them
   */
  void setLineNumbersShown(boolean shown);

  /**
   * Shows the right margin line in every editor of the window, later ones included.
   *
   * @param column the column the line sits left of, or 0 to hide it
   */
  void setRightMargin(int column);

  /**
   * Shows or hides the indent guides in every editor of the window, later ones included.
   *
   * @param shown true to show them
   */
  void setIndentGuides(boolean shown);

  /**
   * Shows or hides the modified-line marks in every editor of the window, later ones included; each
   * editor reports it through {@link EditorInput#lineChangesShown}.
   *
   * @param shown true to show them
   */
  void setLineChangesShown(boolean shown);

  /**
   * Opens the Document Statistics dialog, which calls {@link StatisticsInput#closed} once it
   * closes.
   *
   * @param input runs its Refresh button
   * @return the dialog, for the counts
   */
  StatisticsDisplay showStatistics(StatisticsInput input);

  /**
   * Opens a modeless Tools > Generate dialog: a text field, its hash and Copy. The dialog calls
   * {@link HashTextInput#closed} once it closes.
   *
   * @param title the dialog title
   * @param note what the hash covers, and for MD5 or SHA-1 that it is no security check
   * @param input receives the field's text and the buttons
   * @return the dialog
   */
  HashTextDisplay showHashText(String title, String note, HashTextInput input);

  /**
   * Opens a modeless checksum list dialog: the lines, progress with Cancel, a status line and the
   * buttons Choose, Copy, Save As and Close. The dialog calls {@link ChecksumInput#closed} once it
   * closes.
   *
   * @param title the dialog title
   * @param note shown above the lines, or empty
   * @param chooseLabel the Choose button's label
   * @param input runs the buttons
   * @return the dialog
   */
  ChecksumDisplay showChecksums(String title, String note, String chooseLabel, ChecksumInput input);

  /**
   * Asks which date and time format Insert Date and Time inserts.
   *
   * @param rows each format with the current time in it
   * @param selected the row selected at first
   * @param chosen receives the chosen row; not called on cancel
   */
  void chooseDateFormat(List<String> rows, int selected, Consumer<Integer> chosen);

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

  /**
   * Shows the print dialog. A canceled dialog calls nothing; a system without a printer, or a
   * printer that fails to open, shows a notice and calls nothing.
   *
   * @param chosen receives the job, which it must close
   */
  void choosePrinter(Consumer<PrintTarget> chosen);

  /**
   * Shows the window's print row, or updates it.
   *
   * @param text what is printing
   * @param percent the progress, 0 to 100
   * @param cancel what the row's Cancel runs
   */
  void showPrintProgress(String text, int percent, Runnable cancel);

  /** Hides the print row. */
  void hidePrintProgress();

  /**
   * Reports whether {@link #setOnTop} takes effect on this platform.
   *
   * @return false where the window system or toolkit offers no way, or after a call failed
   */
  boolean onTopSupported();

  /**
   * Keeps the window above other windows, or returns it to normal stacking, without moving or
   * activating it. Does nothing where unsupported.
   *
   * @param onTop true to keep it above
   */
  void setOnTop(boolean onTop);
}
