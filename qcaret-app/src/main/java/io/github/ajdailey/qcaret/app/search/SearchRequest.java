package io.github.ajdailey.qcaret.app.search;

import io.github.ajdailey.qcaret.app.view.SearchScope;
import io.github.ajdailey.qcaret.core.search.FileFilter;
import io.github.ajdailey.qcaret.core.search.SearchQuery;
import java.nio.file.Path;

/**
 * One HyperSearch as the user asked for it; a rerun repeats it.
 *
 * @param text the literal text or the regex
 * @param regex true for a regex
 * @param caseSensitive false to fold case
 * @param wholeWord true for whole words
 * @param scope what is read
 * @param folder the folder of a folder scope, absolute and normalized; null otherwise
 * @param include file masks a file must match; blank for every file
 * @param exclude file masks no file may match
 * @param excludedDirectories directory names skipped with their trees
 * @param hidden true to read hidden files and directories
 */
public record SearchRequest(
    String text,
    boolean regex,
    boolean caseSensitive,
    boolean wholeWord,
    SearchScope scope,
    Path folder,
    String include,
    String exclude,
    String excludedDirectories,
    boolean hidden) {
  /**
   * Returns the query.
   *
   * @return the query
   */
  public SearchQuery query() {
    return new SearchQuery(text, regex, caseSensitive, wholeWord);
  }

  /**
   * Parses the masks.
   *
   * @return the filter
   * @throws IllegalArgumentException for a mask that does not parse
   */
  public FileFilter filter() {
    return FileFilter.of(include, exclude, excludedDirectories, hidden);
  }
}
