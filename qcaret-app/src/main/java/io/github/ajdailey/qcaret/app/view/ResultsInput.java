package io.github.ajdailey.qcaret.app.view;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The results dock's model and actions: retained result sets, newest first, each holding file
 * groups that hold matches. Indexes are read again after each {@link SearchView#resultsChanged}.
 * Called on the UI thread.
 */
public interface ResultsInput {
  /** An item of the table's context menu, with its label. */
  enum RowAction {
    /** Opens the row's match, or a group's first match. */
    OPEN(UiText.RESULTS_MENU_OPEN),
    /** Copies the row's matches: {@link #copy}. */
    COPY(UiText.RESULTS_MENU_COPY),
    /** Copies every set's matches: {@link #copyAll}. */
    COPY_ALL(UiText.RESULTS_MENU_COPY_ALL),
    /** Runs the row's set again. */
    RERUN(UiText.RESULTS_MENU_RERUN),
    /** Pins or unpins the row's set; a check item. */
    PIN(UiText.RESULTS_MENU_PIN),
    /** Removes the row's set, as Clear. */
    REMOVE_SET(UiText.RESULTS_MENU_REMOVE_SET),
    /** Expands the row's set and each of its groups. */
    EXPAND_ALL(UiText.RESULTS_MENU_EXPAND_ALL),
    /** Collapses each group of the row's set and the set. */
    COLLAPSE_ALL(UiText.RESULTS_MENU_COLLAPSE_ALL);

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

  /** The table's context menu in order; null is a separator. */
  List<RowAction> MENU =
      Collections.unmodifiableList(
          Arrays.asList(
              RowAction.OPEN,
              RowAction.COPY,
              RowAction.COPY_ALL,
              null,
              RowAction.RERUN,
              RowAction.PIN,
              RowAction.REMOVE_SET,
              null,
              RowAction.EXPAND_ALL,
              RowAction.COLLAPSE_ALL));

  /**
   * Counts the retained sets.
   *
   * @return the count
   */
  int setCount();

  /**
   * Returns a set's id, which a rerun keeps.
   *
   * @param set the set's index
   * @return the id
   */
  long setId(int set);

  /**
   * Returns a set's row: its query, scope, counts and state.
   *
   * @param set the set's index
   * @return the row
   */
  ResultLabel set(int set);

  /**
   * Counts a set's file groups.
   *
   * @param set the set's index
   * @return the count
   */
  int fileCount(int set);

  /**
   * Returns a file group's row: its name, folder and match count.
   *
   * @param set the set's index
   * @param file the group's index
   * @return the row
   */
  ResultLabel file(int set, int file);

  /**
   * Counts a file group's matches.
   *
   * @param set the set's index
   * @param file the group's index
   * @return the count
   */
  int matchCount(int set, int file);

  /**
   * Returns a match's row: its line number and snippet, the match highlighted.
   *
   * @param set the set's index
   * @param file the group's index
   * @param match the match's index
   * @return the row
   */
  ResultLabel match(int set, int file, int match);

  /**
   * Opens a match's document and selects the match.
   *
   * @param set the set's index
   * @param file the group's index
   * @param match the match's index
   */
  void open(int set, int file, int match);

  /**
   * A file group was expanded: its file is checked for changes.
   *
   * @param set the set's index
   * @param file the group's index
   */
  void expanded(int set, int file);

  /**
   * Runs a set's search again.
   *
   * @param set the set's index
   */
  void rerun(int set);

  /**
   * Stops a set's running search.
   *
   * @param set the set's index
   */
  void stop(int set);

  /**
   * Pins a set, or unpins it; a pinned set is kept past the retained limit.
   *
   * @param set the set's index
   */
  void togglePin(int set);

  /**
   * Reports whether a set is pinned.
   *
   * @param set the set's index
   * @return true when pinned
   */
  boolean pinned(int set);

  /**
   * Reports whether a set's search runs.
   *
   * @param set the set's index
   * @return true while it runs
   */
  boolean running(int set);

  /**
   * Removes a set, stopping its search.
   *
   * @param set the set's index
   */
  void clear(int set);

  /** Removes every set. */
  void clearAll();

  /** Hides the dock. */
  void hide();

  /**
   * Searches a stale group's file or document again; the group's matches are replaced by the new
   * ones.
   *
   * @param set the set's index
   * @param file the group's index
   */
  void rerunFile(int set, int file);

  /**
   * Puts a set's, a group's or one match's lines on the clipboard, one per match as {@code
   * path:line: text}. A large copy builds on a worker and replaces the clipboard when done.
   *
   * @param set the set's index
   * @param file the group's index, or -1 for every group of the set
   * @param match the match's index, or -1 for every match of the group
   */
  void copy(int set, int file, int match);

  /** Puts every set's lines on the clipboard, as {@link #copy} does for one set. */
  void copyAll();

  /**
   * Opens the replace preview for a set's matches.
   *
   * @param set the set's index
   */
  void replace(int set);
}
