package io.github.ajdailey.qcaret.core.markdown;

/**
 * A leaf block's rendered lines. {@link RenderedMarkdown#blocks} lists every leaf block in rendered
 * order; the lines between two blocks are separator lines. Quotes, lists and alerts are not blocks:
 * their nesting is in each line's {@link RenderedMarkdown#quoteDepth} and {@link
 * RenderedMarkdown#listDepth}.
 *
 * @param kind the block's kind
 * @param firstLine the first rendered line
 * @param lineCount the lines, at least 1
 */
public record MarkdownBlock(Kind kind, int firstLine, int lineCount) {
  /** What a block holds. */
  public enum Kind {
    /** Inline text; images render inside it. A hard line break adds a line. */
    PARAGRAPH,
    /** One heading line. */
    HEADING,
    /** One empty line drawn as a rule, also the rule above the footnotes. */
    RULE,
    /** Code lines, one per source line; YAML front matter too. */
    CODE,
    /** HTML block lines shown as source text. */
    HTML,
    /** Table rows, one line each, header first. */
    TABLE,
    /** An alert's title line. */
    ALERT_TITLE,
    /** A table of contents, one line per heading. */
    TOC,
    /** The one empty line of an empty list item or quote. */
    EMPTY
  }
}
