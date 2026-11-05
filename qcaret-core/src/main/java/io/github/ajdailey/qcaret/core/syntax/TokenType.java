package io.github.ajdailey.qcaret.core.syntax;

/** The fixed token set a grammar assigns; text no rule covers is plain and has no token. */
public enum TokenType {
  /** Line and block comments. */
  COMMENT,
  /** String and character literals. */
  STRING,
  /** Escape sequences inside a string. */
  ESCAPE,
  /** Language keywords. */
  KEYWORD,
  /** A second keyword list, such as built-in functions. */
  KEYWORD2,
  /** Type names. */
  TYPE,
  /** Numeric literals. */
  NUMBER,
  /** Named constants. */
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
  INVALID;

  private static final TokenType[] VALUES = values();

  /**
   * Returns the type a grammar file names.
   *
   * @param name the lower-case name, such as {@code keyword2}
   * @return the type
   * @throws IllegalArgumentException for an unknown name
   */
  public static TokenType fromName(String name) {
    for (TokenType type : VALUES) {
      if (GrammarSet.asciiLower(type.name()).equals(name)) {
        return type;
      }
    }
    throw new IllegalArgumentException("unknown token " + name);
  }

  /**
   * Returns the type with an ordinal.
   *
   * @param ordinal from 0 to the type count
   * @return the type
   */
  public static TokenType of(int ordinal) {
    return VALUES[ordinal];
  }
}
