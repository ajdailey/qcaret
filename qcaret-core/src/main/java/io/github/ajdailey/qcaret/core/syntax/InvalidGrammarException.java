package io.github.ajdailey.qcaret.core.syntax;

/** A grammar file that does not parse, fails the schema, or holds a bad regex or reference. */
public final class InvalidGrammarException extends Exception {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong, with the line where the parser reports one
   * @param cause the parser's or the regex compiler's exception, or null
   */
  public InvalidGrammarException(String message, Throwable cause) {
    super(message, cause);
  }
}
