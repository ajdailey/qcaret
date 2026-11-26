package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HexPatternTest {
  @Test
  void pairsParseWithOrWithoutSpaces() throws HexPatternException {
    HexPattern spaced = HexPattern.hex(" 4D 5a ?? 00\t");
    HexPattern packed = HexPattern.hex("4d5A??00");
    for (HexPattern pattern : new HexPattern[] {spaced, packed}) {
      assertEquals(4, pattern.length());
      assertEquals(0x4d, pattern.byteAt(0));
      assertEquals(0x5a, pattern.byteAt(1));
      assertTrue(pattern.wildcard(2));
      assertFalse(pattern.wildcard(3));
      assertTrue(pattern.matches(new byte[] {9, 0x4d, 0x5a, 0x77, 0}, 1));
      assertFalse(pattern.matches(new byte[] {9, 0x4d, 0x5a, 0x77, 1}, 1));
    }
  }

  @Test
  void aPatternStartingWithAWildcardMatches() throws HexPatternException {
    HexPattern pattern = HexPattern.hex("?? FF");
    assertTrue(pattern.matches(new byte[] {1, (byte) 0xff}, 0));
    assertFalse(pattern.matches(new byte[] {(byte) 0xff, 1}, 0));
  }

  @Test
  void badPatternsNameTheReasonAndPlace() {
    assertRefused("", HexPatternException.Reason.EMPTY, -1);
    assertRefused("   ", HexPatternException.Reason.EMPTY, -1);
    assertRefused("4D 5", HexPatternException.Reason.ODD_DIGITS, 3);
    assertRefused("4 D", HexPatternException.Reason.ODD_DIGITS, 0);
    assertRefused("4D ?", HexPatternException.Reason.ODD_DIGITS, 3);
    assertRefused("4G", HexPatternException.Reason.NOT_HEX, 1);
    assertRefused("?A", HexPatternException.Reason.NOT_HEX, 0);
    assertRefused("A?", HexPatternException.Reason.NOT_HEX, 1);
    assertRefused("0x4D", HexPatternException.Reason.NOT_HEX, 1);
    assertRefused("?? ??", HexPatternException.Reason.WILDCARDS_ONLY, -1);
    assertRefused("00".repeat(HexPattern.MAX_LENGTH + 1), HexPatternException.Reason.TOO_LONG, 512);
  }

  @Test
  void theLongestPatternParses() throws HexPatternException {
    assertEquals(
        HexPattern.MAX_LENGTH, HexPattern.hex("AB".repeat(HexPattern.MAX_LENGTH)).length());
  }

  @Test
  void textEncodesInTheSourceCharset() throws HexPatternException {
    HexPattern utf16 = HexPattern.text("Az", StandardCharsets.UTF_16LE);
    assertEquals(4, utf16.length());
    assertEquals('A', utf16.byteAt(0));
    assertEquals(0, utf16.byteAt(1));
    assertFalse(utf16.wildcard(1));
    HexPattern sjis = HexPattern.text("あ", Charset.forName("Shift_JIS"));
    assertEquals(2, sjis.length());
    assertEquals((byte) 0x82, sjis.byteAt(0));
    assertEquals((byte) 0xa0, sjis.byteAt(1));
  }

  @Test
  void badTextNamesTheReasonAndPlace() {
    assertTextRefused("", StandardCharsets.UTF_8, HexPatternException.Reason.EMPTY, -1);
    assertTextRefused("ab€", StandardCharsets.ISO_8859_1, HexPatternException.Reason.UNMAPPABLE, 2);
    assertTextRefused("a\udc00", StandardCharsets.UTF_8, HexPatternException.Reason.UNMAPPABLE, 1);
    assertTextRefused(
        "é".repeat(129), StandardCharsets.UTF_8, HexPatternException.Reason.TOO_LONG, 128);
  }

  private static void assertRefused(
      final String text, HexPatternException.Reason reason, int index) {
    HexPatternException e =
        assertThrows(
            HexPatternException.class,
            new Executable() {
              @Override
              public void execute() throws HexPatternException {
                HexPattern.hex(text);
              }
            },
            text);
    assertEquals(reason, e.reason(), text);
    assertEquals(index, e.index(), text);
  }

  private static void assertTextRefused(
      final String text, final Charset charset, HexPatternException.Reason reason, int index) {
    HexPatternException e =
        assertThrows(
            HexPatternException.class,
            new Executable() {
              @Override
              public void execute() throws HexPatternException {
                HexPattern.text(text, charset);
              }
            });
    assertEquals(reason, e.reason());
    assertEquals(index, e.index());
  }
}
