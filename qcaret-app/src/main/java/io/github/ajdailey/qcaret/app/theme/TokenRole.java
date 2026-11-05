package io.github.ajdailey.qcaret.app.theme;

/** What a token is, for its color. Text that is no token takes {@link Palette#text()}. */
public enum TokenRole {
  /** Line and block comments. */
  COMMENT,
  /** String and character literals. */
  STRING,
  /** Escape sequences inside a string. */
  ESCAPE,
  /** Language keywords. */
  KEYWORD,
  /** A grammar's second keyword list, such as built-in functions. */
  KEYWORD2,
  /** Type names. */
  TYPE,
  /** Numeric literals. */
  NUMBER,
  /** Named constants such as {@code true} and {@code null}. */
  CONSTANT,
  /** Operators. */
  OPERATOR,
  /** Brackets, separators and other punctuation. */
  DELIMITER,
  /** Function and method names. */
  FUNCTION,
  /** Preprocessor directives and annotations. */
  PREPROCESSOR,
  /** Markup tag names. */
  TAG,
  /** Markup attribute names. */
  ATTRIBUTE,
  /** Markup headings. */
  HEADING,
  /** Markup emphasis. */
  EMPHASIS,
  /** Links and URLs. */
  LINK,
  /** Text the grammar marks as an error. */
  INVALID
}
