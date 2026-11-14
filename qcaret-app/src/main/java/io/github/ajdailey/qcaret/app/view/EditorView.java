package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.editor.Bookmarks;
import io.github.ajdailey.qcaret.app.editor.LineChanges;
import io.github.ajdailey.qcaret.app.editor.OverviewMarks;
import io.github.ajdailey.qcaret.app.editor.TokenSource;
import java.util.List;

/**
 * One editor view of a document as its controller sees it; a split tab holds two. Called on the UI
 * thread.
 */
public interface EditorView {
  /** Scrolls the view so the primary caret shows. */
  void showCaret();

  /**
   * Returns the display row at the top of the view.
   *
   * @return the row, from 0
   */
  int topRow();

  /**
   * Scrolls so a row is the first visible one, as soon as the view has a size.
   *
   * @param row the display row
   */
  void scrollToRow(int row);

  /** Focuses the view. */
  void focus();

  /**
   * Shows the find matches; replaces the ones shown before.
   *
   * @param ranges model offset pairs, start then end, ascending; empty for none
   */
  void showMatches(int[] ranges);

  /**
   * Returns the view's pixel and column queries.
   *
   * @return the layout
   */
  ViewLayout layout();

  /**
   * Sets the view's columns per tab stop.
   *
   * @param columns from 1 to 16
   */
  void setTabWidth(int columns);

  /**
   * Turns column selection mode on or off: a plain mouse drag then selects a block.
   *
   * @param on true for column mode
   */
  void setColumnMode(boolean on);

  /**
   * Sets the syntax tokens the view paints.
   *
   * @param tokens the document's tokens, or null for none
   */
  void setSyntax(TokenSource tokens);

  /** Repaints the visible text after the syntax tokens changed. */
  void redrawHighlights();

  /**
   * Shows the busy cursor over the view, or the text cursor again; driven by the editor's operation
   * counter.
   *
   * @param busy true while any of the editor's worker commands is active
   */
  void setBusy(boolean busy);

  /**
   * Shows the bracket at the caret and its partner; replaces the ones shown before.
   *
   * @param ranges model offset pairs, ascending; empty for none
   */
  void showBrackets(int[] ranges);

  /**
   * Shows the occurrences of the word at the caret; replaces the ones shown before.
   *
   * @param ranges model offset pairs, ascending; empty for none
   */
  void showOccurrences(int[] ranges);

  /**
   * Sets the document's bookmarks, which the view marks in its gutter and follows.
   *
   * @param bookmarks the bookmarks
   */
  void setBookmarks(Bookmarks bookmarks);

  /**
   * Turns the whitespace and line-ending marks on or off.
   *
   * @param whitespace true to mark spaces and tabs
   * @param lineEndings true to mark each line break with its kind
   */
  void setMarks(boolean whitespace, boolean lineEndings);

  /**
   * Shows the word completion list below the primary caret, or hides it.
   *
   * @param words the words, best first; empty hides the list
   * @param selected the index of the selected word
   */
  void showCompletions(List<String> words, int selected);

  /**
   * Shows the overview strip's marks; replaces the ones shown before.
   *
   * @param marks the marks, computed for the strip's reported height
   */
  void showOverview(OverviewMarks marks);

  /**
   * Shows or hides the overview strip.
   *
   * @param shown true to show
   */
  void setOverviewShown(boolean shown);

  /**
   * Shows the gutter's modified-line marks; replaces the ones shown before.
   *
   * @param changes the marks, {@link LineChanges#NONE} for none
   */
  void showLineChanges(LineChanges changes);
}
