package io.github.ajdailey.qcaret.app.config;

/**
 * The search options shared by the find bar and HyperSearch, as {@code search.*} keeps them.
 *
 * @param regex regex rather than literal text
 * @param caseSensitive match case
 * @param wholeWord whole words only
 * @param hiddenFiles a directory search visits hidden files; only HyperSearch offers it
 */
public record SearchToggles(
    boolean regex, boolean caseSensitive, boolean wholeWord, boolean hiddenFiles) {
  /** Every toggle off. */
  public static final SearchToggles DEFAULTS = new SearchToggles(false, false, false, false);
}
