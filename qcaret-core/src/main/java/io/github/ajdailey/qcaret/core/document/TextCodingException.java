package io.github.ajdailey.qcaret.core.document;

import java.io.IOException;

/**
 * A load met bytes its charset cannot decode, or a save met a char its charset cannot encode. A
 * save fails before any file is touched.
 */
public final class TextCodingException extends IOException {
  private static final long serialVersionUID = 1L;

  private final long offset;
  private final String charset;

  /**
   * Creates the exception.
   *
   * @param message what failed
   * @param offset the byte offset in the file (load) or the char offset in the text (save)
   * @param charset the charset name
   */
  public TextCodingException(String message, long offset, String charset) {
    super(message + " at " + offset + " in " + charset);
    this.offset = offset;
    this.charset = charset;
  }

  /**
   * Returns where decoding or encoding failed.
   *
   * @return a byte offset for a load, a char offset for a save
   */
  public long offset() {
    return offset;
  }

  /**
   * Returns the charset.
   *
   * @return its name
   */
  public String charset() {
    return charset;
  }
}
