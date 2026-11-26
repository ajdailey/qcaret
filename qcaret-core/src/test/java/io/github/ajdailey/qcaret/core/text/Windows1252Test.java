package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class Windows1252Test {
  private static final Charset JDK = Charset.forName("windows-1252");

  private static final int[] UNDEFINED = {0x81, 0x8d, 0x8f, 0x90, 0x9d};

  /** Returns bytes 0x00 to 0xFF in order. */
  static byte[] everyByte() {
    byte[] all = new byte[256];
    for (int b = 0; b < 256; b++) {
      all[b] = (byte) b;
    }
    return all;
  }

  @Test
  void everyByteDecodesToOneCharAndRoundTrips() throws CharacterCodingException {
    byte[] all = everyByte();
    String text = Windows1252.INSTANCE.newDecoder().decode(ByteBuffer.wrap(all)).toString();
    assertEquals(256, text.length());
    assertEquals(-1, text.indexOf('�'));
    String jdk = new String(all, JDK);
    for (int b = 0; b < 256; b++) {
      boolean undefined = false;
      for (int u : UNDEFINED) {
        undefined |= u == b;
      }
      assertEquals(undefined ? (char) b : jdk.charAt(b), text.charAt(b), Integer.toHexString(b));
    }
    assertEquals('€', text.charAt(0x80));
    ByteBuffer encoded = Windows1252.INSTANCE.newEncoder().encode(CharBuffer.wrap(text));
    byte[] back = new byte[encoded.remaining()];
    encoded.get(back);
    assertArrayEquals(all, back);
    assertArrayEquals(all, text.getBytes(Windows1252.INSTANCE));
  }

  @Test
  void anUnmappedCharDoesNotEncode() {
    CharsetEncoder encoder = Windows1252.INSTANCE.newEncoder();
    assertTrue(encoder.canEncode('€'));
    assertTrue(encoder.canEncode('\u0081'));
    assertFalse(encoder.canEncode('Ā'));
    assertFalse(encoder.canEncode('\u0080'));
    assertFalse(encoder.canEncode('�'));
    assertEquals(1.0f, encoder.maxBytesPerChar());
    assertThrows(
        UnmappableCharacterException.class,
        new Executable() {
          @Override
          public void execute() throws CharacterCodingException {
            Windows1252.INSTANCE.newEncoder().encode(CharBuffer.wrap("a中"));
          }
        });
  }

  @Test
  void replacesTheJdkCharsetByName() {
    assertEquals(JDK, Windows1252.INSTANCE);
    assertSame(Windows1252.INSTANCE, Windows1252.substitute(JDK));
    assertSame(StandardCharsets.ISO_8859_1, Windows1252.substitute(StandardCharsets.ISO_8859_1));
    assertSame(Windows1252.INSTANCE, TextEncoding.of(JDK, false).charset());
    assertSame(Windows1252.INSTANCE, new TextEncoding(JDK, false).charset());
    assertEquals(new TextEncoding(JDK, false), TextEncoding.of(Windows1252.INSTANCE, false));
    assertSame(Windows1252.INSTANCE, TextEncoding.forName("cp1252").charset());
    assertNotEquals(
        JDK.newDecoder().getClass(), TextEncoding.of(JDK, false).charset().newDecoder().getClass());
  }

  @Test
  void charsetsResolvesEveryAliasToTheWrapper() {
    assertEquals(JDK.aliases(), Windows1252.INSTANCE.aliases());
    assertSame(Windows1252.INSTANCE, Charsets.forName("windows-1252"));
    assertSame(Windows1252.INSTANCE, Charsets.forName("WINDOWS-1252"));
    for (String alias : JDK.aliases()) {
      assertSame(Windows1252.INSTANCE, Charsets.forName(alias), alias);
    }
    assertSame(Windows1252.INSTANCE, Charsets.available().get("windows-1252"));
    assertSame(StandardCharsets.UTF_8, Charsets.forName("utf-8"));
    assertEquals(Charset.availableCharsets().keySet(), Charsets.available().keySet());
    assertSame(Windows1252.INSTANCE, Modelines.charset("cp1252"));
  }
}
