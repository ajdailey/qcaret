package io.github.ajdailey.qcaret.core.format;

/**
 * Why an XML Pretty Print or Minify run changed nothing: the first content, in text order, that
 * stays as written though it holds an element, comment or PI.
 *
 * @param kind why the content stays
 * @param element the element's name as written; null for a fragment's top level
 * @param offset the element's start tag, or the top level's first text or CDATA, in the whole text
 * @param line its line, from 1
 * @param character for {@link Kind#TEXT}, the first char of the text outside XML whitespace when
 *     every such char is invisible (another space, a format or a control char, such as U+00A0);
 *     otherwise -1
 */
public record KeptContent(Kind kind, String element, int offset, int line, int character) {
  /** Why content stays as written. */
  public enum Kind {
    /** The content holds non-whitespace text. */
    TEXT,
    /** The content holds CDATA and no text. */
    CDATA,
    /** {@code xml:space="preserve"} applies to the element. */
    PRESERVE
  }
}
