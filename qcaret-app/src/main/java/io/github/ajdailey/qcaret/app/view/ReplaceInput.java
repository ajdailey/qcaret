package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * The replace preview's model and actions: the files of a result set with their matches, each of
 * which can be unchecked, the replacement text, and the before and after view of the selected file.
 * Indexes are read again after each {@link SearchView#replacePreviewChanged}. Called on the UI
 * thread.
 */
public interface ReplaceInput {
  /**
   * Returns the line naming the search: its text, scope and counts.
   *
   * @return the line
   */
  String title();

  /**
   * Returns the replacement text.
   *
   * @return the text
   */
  String replacement();

  /**
   * Sets the replacement text; the preview is computed again.
   *
   * @param text the text; for a regex, {@code $n}, {@code ${name}}, {@code \n} and {@code \t}
   */
  void setReplacement(String text);

  /**
   * Counts the files.
   *
   * @return the count
   */
  int fileCount();

  /**
   * Returns a file's row: its name, folder and checked match count.
   *
   * @param file the file's index
   * @return the row
   */
  String file(int file);

  /**
   * Reports whether a file is replaced in.
   *
   * @param file the file's index
   * @return true when checked
   */
  boolean fileChecked(int file);

  /**
   * Checks or unchecks a file.
   *
   * @param file the file's index
   * @param checked true to replace in it
   */
  void setFileChecked(int file, boolean checked);

  /**
   * Counts a file's matches.
   *
   * @param file the file's index
   * @return the count
   */
  int matchCount(int file);

  /**
   * Returns a match's row: its line and snippet, the match highlighted.
   *
   * @param file the file's index
   * @param match the match's index
   * @return the row
   */
  ResultLabel match(int file, int match);

  /**
   * Reports whether a match is replaced.
   *
   * @param file the file's index
   * @param match the match's index
   * @return true when checked
   */
  boolean matchChecked(int file, int match);

  /**
   * Checks or unchecks a match.
   *
   * @param file the file's index
   * @param match the match's index
   * @param checked true to replace it
   */
  void setMatchChecked(int file, int match, boolean checked);

  /**
   * Selects the file whose before and after view shows; it is computed on a worker.
   *
   * @param file the file's index
   */
  void select(int file);

  /**
   * Returns the selected file's before and after view.
   *
   * @return the lines, or null while they are computed
   */
  List<PreviewLine> preview();

  /**
   * Describes what Apply would do, or why it cannot run.
   *
   * @return one line
   */
  String summary();

  /**
   * Reports whether Apply can run: the replacement parses and a match is checked.
   *
   * @return true when Apply is enabled
   */
  boolean canApply();

  /**
   * Starts the replace of the checked matches; refused with a notice while another replace or a
   * restore runs.
   *
   * @return true when the replace started; the preview then closes
   */
  boolean apply();

  /** The preview closed without Apply. */
  void closed();
}
