package io.github.ajdailey.qcaret.app.view;

/** One document tab and its editor as the controllers see it. Called on the UI thread. */
public interface TabView {
  /**
   * Sets the tab's label.
   *
   * @param title the file name, with a leading {@code *} when dirty
   * @param toolTip the full path, or the title for an untitled document
   */
  void setTitle(String title, String toolTip);

  /** Shows the tab and focuses its editor. */
  void select();

  /** Scrolls the editor so the primary caret shows. */
  void showCaret();

  /** Removes the tab and disposes its editor. */
  void close();
}
