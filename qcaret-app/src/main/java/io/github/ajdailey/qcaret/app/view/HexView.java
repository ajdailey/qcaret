package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.core.hex.HexRows;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A hex view: a tab's hex pane or a Hex Viewer window. Display requests only, called on the UI
 * thread; the view paints the rows {@link HexInput#row} returns and forwards input. The {@link
 * ActivityIndicator} calls raise and drop the view's busy cursor.
 */
public interface HexView extends ActivityIndicator {
  /**
   * Sets the layout: the source size, bytes per row and offset width. The view repaints.
   *
   * @param rows the layout
   */
  void showLayout(HexRows rows);

  /**
   * Sets the first visible row and the scroll bar.
   *
   * @param scroll the scroll state
   */
  void showScroll(HexScroll scroll);

  /**
   * Draws the caret and the selection.
   *
   * @param selection the caret, selection and caret column
   */
  void showSelection(HexSelection selection);

  /** Repaints the visible rows; rows that were missing may now be cached. */
  void redraw();

  /**
   * Dims the rows while the bytes they show are being encoded again after an edit.
   *
   * @param dimmed true while stale
   */
  void setDimmed(boolean dimmed);

  /**
   * Shows the status line.
   *
   * @param text the caret offset, the selection and the size
   */
  void showStatus(String text);

  /**
   * Shows a notice bar above the rows, replacing any shown.
   *
   * @param message the notice, or null to hide the bar
   * @param action the label of a button that calls {@link HexInput#noticeAction}, or null for none
   */
  void showNotice(String message, String action);

  /**
   * Shows a one-line message below the find field: a find result, a refusal, an error. Empty clears
   * it.
   *
   * @param text the message
   */
  void showMessage(String text);

  /**
   * Shows the find bar and focuses its field.
   *
   * @param text the field's text
   * @param hex true for a hex pattern, false for text
   */
  void showFind(String text, boolean hex);

  /**
   * Asks for an offset to move the caret to.
   *
   * @param valid accepts the text OK may submit
   * @param answer receives the text; not called on cancel
   */
  void askGoToOffset(Predicate<String> valid, Consumer<String> answer);

  /** Moves the keyboard focus to the rows. */
  void focus();
}
