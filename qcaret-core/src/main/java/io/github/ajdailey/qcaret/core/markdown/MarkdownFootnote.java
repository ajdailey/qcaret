package io.github.ajdailey.qcaret.core.markdown;

/**
 * A footnote. Footnotes are numbered from 1 in the order of their first reference; a reference
 * renders as its number under a {@link InlineStyle#SUP} run, and the definitions render after the
 * document's last block, below a rule, as numbered list items. A definition no reference names is
 * not rendered.
 *
 * @param label the label as written, {@code [^label]}
 * @param number the number
 * @param line the rendered first line of the definition
 */
public record MarkdownFootnote(String label, int number, int line) {
  /**
   * A reference to a footnote.
   *
   * @param start the first rendered char of the number
   * @param end the char after the last
   * @param number the footnote's number
   */
  public record Reference(int start, int end, int number) {}
}
