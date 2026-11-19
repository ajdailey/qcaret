package io.github.ajdailey.qcaret.core.format;

/**
 * How {@link Formats} lays text out.
 *
 * @param indent one indent level: a tab, or spaces
 * @param lineEnding the line break Pretty Print inserts
 * @param strictJson true to accept RFC 8259 JSON only; false also accepts line and block comments
 * @param maxChars the longest output of one range; a longer one fails
 */
public record FormatLayout(String indent, String lineEnding, boolean strictJson, int maxChars) {
  /** Checks the components. */
  public FormatLayout {
    if (indent == null || lineEnding == null || lineEnding.isEmpty()) {
      throw new IllegalArgumentException("missing indent or line ending");
    }
    if (maxChars < 0) {
      throw new IllegalArgumentException("negative maxChars");
    }
  }
}
