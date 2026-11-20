package io.github.ajdailey.qcaret.core.markdown;

/** The style a {@link StyleRun} applies to rendered text. */
public enum InlineStyle {
  /** A heading line; the run's level is 1 to 6. */
  HEADING,
  /** {@code **strong**}. */
  STRONG,
  /** {@code *emphasis*}. */
  EMPHASIS,
  /** {@code ~~strikethrough~~}. */
  STRIKETHROUGH,
  /** A code span. */
  CODE,
  /** Link text; {@link RenderedMarkdown#links} holds the target. */
  LINK,
  /** {@code <kbd>}. */
  KBD,
  /** {@code <sub>}. */
  SUB,
  /** {@code <sup>}. */
  SUP
}
