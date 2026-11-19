package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The Bookmarks list's model and actions: the selected document's bookmarks by line. Indexes are
 * read again after each {@link BookmarksView#bookmarksChanged}. Called on the UI thread.
 */
public interface BookmarksInput {
  /** An item of the list's context menu, with its label. */
  enum RowAction {
    /** {@link #goTo}. */
    GO_TO(UiText.BOOKMARKS_MENU_GO_TO),
    /** {@link #editComment}. */
    EDIT(UiText.BOOKMARKS_MENU_EDIT),
    /** {@link #copyLine}. */
    COPY_LINE(UiText.BOOKMARKS_MENU_COPY_LINE),
    /** {@link #remove}. */
    REMOVE(UiText.BOOKMARKS_MENU_REMOVE),
    /** {@link #clearAll}. */
    CLEAR_ALL(UiText.BOOKMARKS_MENU_CLEAR_ALL);

    private final UiText label;

    RowAction(UiText label) {
      this.label = label;
    }

    /**
     * Returns the menu label.
     *
     * @return the label, with {@code &} before the mnemonic
     */
    public UiText label() {
      return label;
    }
  }

  /** The list's context menu in order; null is a separator. */
  List<RowAction> MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              RowAction.GO_TO,
              RowAction.EDIT,
              RowAction.COPY_LINE,
              RowAction.REMOVE,
              null,
              RowAction.CLEAR_ALL));

  /**
   * Counts the rows.
   *
   * @return the count
   */
  int rowCount();

  /**
   * Returns a row.
   *
   * @param index the row's index
   * @return the row
   */
  BookmarkRow row(int index);

  /**
   * Describes what the list shows: the document's name and the bookmark count, or that rows were
   * left out.
   *
   * @return one line
   */
  String summary();

  /**
   * Shows a row's bookmarked line in the document's focused view, expanding a collapsed region that
   * hides it.
   *
   * @param index the row's index
   */
  void goTo(int index);

  /**
   * Asks for a row's comment and sets it.
   *
   * @param index the row's index
   */
  void editComment(int index);

  /**
   * Removes a row's bookmark and comment.
   *
   * @param index the row's index
   */
  void remove(int index);

  /**
   * Puts a row's whole line on the clipboard, without its line ending, as the document holds it
   * now. A long line is read on a worker.
   *
   * @param index the row's index
   */
  void copyLine(int index);

  /** Removes every bookmark of the listed document, as Search > Bookmarks > Clear Bookmarks. */
  void clearAll();

  /** Hides the list. */
  void hide();
}
