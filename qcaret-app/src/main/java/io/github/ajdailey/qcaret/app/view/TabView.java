package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.DisplayProjection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;

/** One document tab and its editor views as the controllers see it. Called on the UI thread. */
public interface TabView {
  /**
   * Sets the tab's label.
   *
   * @param title the file name, with a leading {@code *} when dirty
   * @param toolTip the full path, or the title for an untitled document
   */
  void setTitle(String title, String toolTip);

  /** Shows the tab and focuses the view that had the focus last. */
  void select();

  /** Scrolls that view so its primary caret shows. */
  void showCaret();

  /** Removes the tab and disposes its views. */
  void close();

  // Split views.

  /**
   * Returns the editor view the tab was created with.
   *
   * @return the first view
   */
  EditorView firstView();

  /**
   * Adds a second editor view of the document beside or below the first, and focuses it.
   *
   * @param projection the new view's display text
   * @param selections the new view's selections
   * @param editor receives the new view's input
   * @param sideBySide true for views side by side, false for one above the other
   * @return the new view
   */
  EditorView split(
      DisplayProjection projection,
      SelectionModel selections,
      EditorInput editor,
      boolean sideBySide);

  /**
   * Arranges a split tab's views side by side or one above the other.
   *
   * @param sideBySide true for side by side
   */
  void setSideBySide(boolean sideBySide);

  /**
   * Returns a split tab's sash weights.
   *
   * @return two weights
   */
  int[] splitWeights();

  /**
   * Sets a split tab's sash weights.
   *
   * @param weights two positive weights
   */
  void setSplitWeights(int[] weights);

  /**
   * Disposes every view but one.
   *
   * @param kept the view that stays
   */
  void unsplit(EditorView kept);
}
