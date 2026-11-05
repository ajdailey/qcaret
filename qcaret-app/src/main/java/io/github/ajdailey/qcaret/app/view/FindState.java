package io.github.ajdailey.qcaret.app.view;

/**
 * What the find bar shows.
 *
 * @param open the bar shows
 * @param replace the replace row shows
 * @param text the search text
 * @param replacement the replacement text
 * @param caseSensitive the Match Case box
 * @param wholeWord the Whole Word box
 * @param regex the Regex box
 * @param replaceEnabled Replace and Replace All are enabled: the document can be edited
 * @param running a search runs on a worker
 * @param notice the last outcome, such as not found or an invalid pattern, or empty
 * @param error true when the notice is a failure: the text matches nothing or does not compile
 */
public record FindState(
    boolean open,
    boolean replace,
    String text,
    String replacement,
    boolean caseSensitive,
    boolean wholeWord,
    boolean regex,
    boolean replaceEnabled,
    boolean running,
    String notice,
    boolean error) {}
