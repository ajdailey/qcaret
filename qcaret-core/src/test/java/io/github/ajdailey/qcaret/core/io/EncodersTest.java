package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** {@link Encoders}: a decode-only charset is not writable; the encoder reports, never replaces. */
class EncodersTest {
  @Test
  void aDecodeOnlyCharsetIsNotWritable() {
    assertTrue(Encoders.writable(StandardCharsets.UTF_8));
    assertTrue(Encoders.writable(StandardCharsets.ISO_8859_1));
    final Charset auto = Charset.forName("x-JISAutoDetect");
    assertFalse(Encoders.writable(auto));
    assertThrows(
        UnsupportedOperationException.class,
        new Executable() {
          @Override
          public void execute() {
            Encoders.reporting(auto);
          }
        });
  }

  @Test
  void anUnmappableCharIsReported() {
    assertThrows(
        UnmappableCharacterException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            Encoders.reporting(StandardCharsets.ISO_8859_1).encode(CharBuffer.wrap("mock €"));
          }
        });
  }
}
