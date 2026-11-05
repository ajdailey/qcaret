package io.github.ajdailey.qcaret.app.view;

/**
 * The results dock's model and actions: retained result sets, newest first, each holding file
 * groups that hold matches. Indexes are read again after each {@link SearchView#resultsChanged}.
 * Called on the UI thread.
 */
public interface ResultsInput {
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
}
