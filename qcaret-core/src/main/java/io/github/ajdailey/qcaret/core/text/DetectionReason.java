package io.github.ajdailey.qcaret.core.text;

/** The rule {@link EncodingDetector} chose an encoding by. */
public enum DetectionReason {
  /** The user, the session or {@code --encoding} named the charset. */
  FORCED,
  /** A byte order mark. */
  BOM,
  /** The zero-byte pattern of UTF-16 without a BOM. */
  UTF16_PATTERN,
  /** An XML declaration, an HTML meta charset or a coding line, confirmed by a strict decode. */
  DECLARED,
  /** The whole file decodes as UTF-8. */
  UTF8_VALID,
  /** No rule matched. */
  FALLBACK
}
