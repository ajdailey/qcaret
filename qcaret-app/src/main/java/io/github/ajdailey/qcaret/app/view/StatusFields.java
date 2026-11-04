package io.github.ajdailey.qcaret.app.view;

/**
 * The status bar's document fields as shown, each empty without a document.
 *
 * @param position the primary caret's line and column
 * @param selection the selected char count, and the caret count when there are several
 * @param encoding the charset the next save writes, with {@code BOM} when it writes one
 * @param lineEnding {@code CRLF}, {@code LF}, {@code CR} or Mixed
 * @param language the syntax in use
 * @param readOnly the read-only marker, or empty for an editable document
 */
public record StatusFields(
    String position,
    String selection,
    String encoding,
    String lineEnding,
    String language,
    String readOnly) {
  /** The fields without a document. */
  public static final StatusFields NONE = new StatusFields("", "", "", "", "", "");
}
