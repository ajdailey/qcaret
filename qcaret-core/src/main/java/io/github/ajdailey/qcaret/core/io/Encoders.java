package io.github.ajdailey.qcaret.core.io;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * Whether a charset can be written, and the encoder that writes it. A decode-only charset (such as
 * {@code x-JISAutoDetect}) has no encoder: {@link Charset#newEncoder} throws {@code
 * UnsupportedOperationException}, so every encoder user checks {@link #writable} first.
 */
public final class Encoders {
  private Encoders() {}

  /**
   * Reports whether a charset has an encoder.
   *
   * @param charset the charset
   * @return false for a decode-only charset
   */
  public static boolean writable(Charset charset) {
    return charset.canEncode();
  }

  /**
   * Returns an encoder that reports a malformed or unmappable char instead of replacing it.
   *
   * @param charset a {@link #writable} charset
   * @return a new encoder
   * @throws UnsupportedOperationException when the charset is not writable
   */
  public static CharsetEncoder reporting(Charset charset) {
    return charset
        .newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
  }
}
