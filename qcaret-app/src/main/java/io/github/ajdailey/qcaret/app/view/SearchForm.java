package io.github.ajdailey.qcaret.app.view;

import java.util.List;

/**
 * The HyperSearch dialog's fields, as it opens and as the user runs it.
 *
 * @param text the search text
 * @param regex the Regex box
 * @param caseSensitive the Match Case box
 * @param wholeWord the Whole Word box
 * @param scope the scope
 * @param directory the directory of the directory and tree scopes
 * @param include file masks a file must match, {@code ;}-separated; blank for every file
 * @param exclude file masks no file may match
 * @param hidden the Hidden Files box
 * @param workspace the window's workspace folder, empty when none is set
 * @param textHistory search texts of this run, newest first
 * @param directoryHistory directories of this run, newest first
 * @param maskHistory include masks of this run, newest first
 */
public record SearchForm(
    String text,
    boolean regex,
    boolean caseSensitive,
    boolean wholeWord,
    SearchScope scope,
    String directory,
    String include,
    String exclude,
    boolean hidden,
    String workspace,
    List<String> textHistory,
    List<String> directoryHistory,
    List<String> maskHistory) {
  /** Keeps unmodifiable copies of the histories. */
  public SearchForm {
    textHistory = List.copyOf(textHistory);
    directoryHistory = List.copyOf(directoryHistory);
    maskHistory = List.copyOf(maskHistory);
  }
}
