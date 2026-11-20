package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * The Outline dock's model and actions: the headings of the selected Markdown tab. Read again after
 * each {@link OutlineView#outlineChanged}. Called on the UI thread.
 */
public interface OutlineInput {
  /**
   * Returns the headings in document order; an entry's parent comes before it.
   *
   * @return the entries, empty for a tab that is not Markdown
   */
  List<OutlineEntry> entries();

  /**
   * Describes what the dock shows: the document's name and the heading count, or why it lists none.
   *
   * @return one line
   */
  String summary();

  /**
   * Returns the entry holding the focused editor's caret: the last heading at or above its line.
   *
   * @return the entry's index, or -1 above the first heading
   */
  int selected();

  /**
   * Moves the caret to an entry's heading and puts the heading at the top of the editor; in Split
   * and Preview the preview scrolls to it.
   *
   * @param index the entry's index
   * @param focus true to focus the editor afterwards
   */
  void goTo(int index, boolean focus);

  /** Hides the dock. */
  void hide();
}
