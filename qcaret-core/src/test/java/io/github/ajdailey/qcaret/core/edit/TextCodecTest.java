package io.github.ajdailey.qcaret.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Each conversion's rules, refusals, supplementary chars, long input and cancellation. */
class TextCodecTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  /** U+1F600, a supplementary char. */
  private static final String GRIN = "😀";

  private static String convert(TextCodec codec, String text) {
    try {
      return codec.convert(text, TOKEN);
    } catch (CodecException e) {
      throw new AssertionError(codec + " refused " + text + ": " + e.reason(), e);
    }
  }

  private static void assertRefused(
      final TextCodec codec, final String text, CodecException.Reason reason) {
    CodecException e =
        assertThrows(
            CodecException.class,
            new Executable() {
              @Override
              public void execute() throws CodecException {
                codec.convert(text, TOKEN);
              }
            },
            text);
    assertEquals(reason, e.reason(), text);
    assertEquals(0, e.selection());
  }

  @Test
  void base64CarriesUtf8BytesOnOneLine() {
    assertEquals("aMOpIPCfmIA=", convert(TextCodec.BASE64_ENCODE, "hé " + GRIN));
    assertEquals("YQ0KYg==", convert(TextCodec.BASE64_ENCODE, "a\r\nb"));
    assertEquals("", convert(TextCodec.BASE64_ENCODE, ""));
    assertEquals("hé " + GRIN, convert(TextCodec.BASE64_DECODE, "aMOpIPCfmIA="));
  }

  @Test
  void base64DecodeSkipsBlanksAndLineBreaksAndTakesNoPadding() {
    assertEquals("a\r\nb", convert(TextCodec.BASE64_DECODE, " YQ0K\r\n\tYg==\n"));
    assertEquals("abb", convert(TextCodec.BASE64_DECODE, "YW\fJi"));
    assertEquals("a", convert(TextCodec.BASE64_DECODE, "YQ"));
  }

  @Test
  void base64DecodeRefusesWhatIsNotBase64OrNotUtf8() {
    assertRefused(TextCodec.BASE64_DECODE, "YQ=!", CodecException.Reason.NOT_BASE64);
    assertRefused(TextCodec.BASE64_DECODE, "Y-_A", CodecException.Reason.NOT_BASE64);
    assertRefused(TextCodec.BASE64_DECODE, "YQ==YQ==", CodecException.Reason.NOT_BASE64);
    assertRefused(TextCodec.BASE64_DECODE, "YQ=", CodecException.Reason.NOT_BASE64);
    assertRefused(TextCodec.BASE64_DECODE, "YWJjZ", CodecException.Reason.NOT_BASE64);
    assertRefused(TextCodec.BASE64_DECODE, "é", CodecException.Reason.NOT_BASE64);
    // 0xFF, and an overlong encoding of '/'.
    assertRefused(TextCodec.BASE64_DECODE, "/w==", CodecException.Reason.NOT_UTF8);
    assertRefused(TextCodec.BASE64_DECODE, "wK8=", CodecException.Reason.NOT_UTF8);
  }

  @Test
  void anUnpairedSurrogateHasNoUtf8FormToEncode() {
    assertRefused(TextCodec.BASE64_ENCODE, "a\uD83D", CodecException.Reason.UNPAIRED_SURROGATE);
    assertRefused(TextCodec.URL_ENCODE, "\uDE00a", CodecException.Reason.UNPAIRED_SURROGATE);
    assertRefused(TextCodec.URL_DECODE, "%41\uD83D", CodecException.Reason.UNPAIRED_SURROGATE);
  }

  @Test
  void urlEncodeLeavesOnlyUnreservedChars() {
    assertEquals(
        "AZaz09-._~%20%2B%2F%3F%25%26%3D", convert(TextCodec.URL_ENCODE, "AZaz09-._~ +/?%&="));
    assertEquals("%C3%A9%F0%9F%98%80%0D%0A", convert(TextCodec.URL_ENCODE, "é" + GRIN + "\r\n"));
  }

  @Test
  void urlDecodeTakesEitherCaseAndKeepsPlus() {
    assertEquals("a b+cé" + GRIN, convert(TextCodec.URL_DECODE, "a%20b+c%c3%A9%F0%9f%98%80"));
    assertEquals("é " + GRIN, convert(TextCodec.URL_DECODE, "é%20" + GRIN));
  }

  @Test
  void urlDecodeRefusesAMalformedEscapeOrInvalidUtf8() {
    assertRefused(TextCodec.URL_DECODE, "100%", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(TextCodec.URL_DECODE, "%4", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(TextCodec.URL_DECODE, "%G1", CodecException.Reason.MALFORMED_ESCAPE);
    // A fullwidth digit is a digit to Character.digit, not to a URL.
    assertRefused(TextCodec.URL_DECODE, "%４1", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(TextCodec.URL_DECODE, "%C3", CodecException.Reason.NOT_UTF8);
    assertRefused(TextCodec.URL_DECODE, "%ED%A0%80", CodecException.Reason.NOT_UTF8);
  }

  @Test
  void xmlEscapeWritesTheFivePredefinedEntities() {
    assertEquals(
        "&lt;a href=&quot;x&quot;&gt;Tom&apos;s &amp; " + GRIN + "&lt;/a&gt;",
        convert(TextCodec.XML_ESCAPE, "<a href=\"x\">Tom's & " + GRIN + "</a>"));
  }

  @Test
  void xmlUnescapeReadsEntitiesAndCharacterReferences() {
    assertEquals(
        "<a>\"'& ÿ€♦ŒΩω",
        convert(
            TextCodec.XML_UNESCAPE,
            "&lt;a&gt;&quot;&apos;&amp;&nbsp;&yuml;&euro;&diams;" + "&OElig;&Omega;&omega;"));
    assertEquals(
        "AA" + GRIN + GRIN, convert(TextCodec.XML_UNESCAPE, "&#65;&#x41;&#X1F600;&#128512;"));
  }

  @Test
  void xmlUnescapeLeavesWhatItDoesNotKnow() {
    for (String kept :
        new String[] {
          "&bogus;",
          "&AMP;",
          "&amp",
          "& amp;",
          "&;",
          "&#;",
          "&#x;",
          "&#xD800;",
          "&#0;",
          "&#x110000;",
          "&#99999999999;",
          "&#12a;",
          "&#x" + "1".repeat(40) + ";",
          "a & b"
        }) {
      assertEquals(kept, convert(TextCodec.XML_UNESCAPE, kept));
    }
    assertEquals("&&", convert(TextCodec.XML_UNESCAPE, "&&amp;"));
  }

  @Test
  void theNamedEntitiesAreHtml401sAndApos() {
    assertEquals(253, XmlEntities.nameCount());
    assertEquals(0xa0, XmlEntities.named("nbsp"));
    assertEquals(0xff, XmlEntities.named("yuml"));
    assertEquals(0x3c2, XmlEntities.named("sigmaf"));
    assertEquals(0x2135, XmlEntities.named("alefsym"));
    assertEquals(0x232a, XmlEntities.named("rang"));
    assertEquals(0x200d, XmlEntities.named("zwj"));
    assertEquals(-1, XmlEntities.named("hellip2"));
  }

  @Test
  void jsonEscapeWritesRfc8259Escapes() {
    assertEquals(
        "say \\\"hi\\\" \\\\ / \\b\\f\\n\\r\\t\\u0000\\u001f\u007f" + GRIN,
        convert(TextCodec.JSON_ESCAPE, "say \"hi\" \\ / \b\f\n\r\t\u0000\u001f\u007f" + GRIN));
    assertEquals("a\\ud83d b\\ude00", convert(TextCodec.JSON_ESCAPE, "a\uD83D b\uDE00"));
  }

  @Test
  void jsonUnescapeReadsEveryEscapeAndEscapedPairs() {
    String escaped = "\\\"\\\\\\/\\b\\f\\n\\r\\t\\" + "u00e9\\" + "uD83D\\" + "ude00" + GRIN;
    assertEquals("\"\\/\b\f\n\r\té" + GRIN + GRIN, convert(TextCodec.JSON_UNESCAPE, escaped));
  }

  @Test
  void jsonUnescapeRefusesAMalformedOrLoneSurrogateEscape() {
    assertRefused(TextCodec.JSON_UNESCAPE, "a\\", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(TextCodec.JSON_UNESCAPE, "\\x", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(TextCodec.JSON_UNESCAPE, "\\" + "u12", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(TextCodec.JSON_UNESCAPE, "\\" + "u12G4", CodecException.Reason.MALFORMED_ESCAPE);
    assertRefused(
        TextCodec.JSON_UNESCAPE, "\\" + "uD83D", CodecException.Reason.LONE_SURROGATE_ESCAPE);
    assertRefused(
        TextCodec.JSON_UNESCAPE, "\\" + "uDE00", CodecException.Reason.LONE_SURROGATE_ESCAPE);
    assertRefused(
        TextCodec.JSON_UNESCAPE,
        "\\" + "uD83D\\" + "u0041",
        CodecException.Reason.LONE_SURROGATE_ESCAPE);
    assertRefused(
        TextCodec.JSON_UNESCAPE, "\\" + "uD83D\uDE00", CodecException.Reason.LONE_SURROGATE_ESCAPE);
  }

  @Test
  void aTextLongerThanTheCheckIntervalRoundTrips() {
    StringBuilder text = new StringBuilder();
    int i = 0;
    while (text.length() < 3 * TextCodec.CHECK_CHARS + 7) {
      text.append("line ").append(i++).append(" <&\"'> %+ é\t").append(GRIN).append("\r\n");
    }
    String original = text.toString();
    assertEquals(original, roundTrip(TextCodec.BASE64_ENCODE, TextCodec.BASE64_DECODE, original));
    assertEquals(original, roundTrip(TextCodec.URL_ENCODE, TextCodec.URL_DECODE, original));
    assertEquals(original, roundTrip(TextCodec.XML_ESCAPE, TextCodec.XML_UNESCAPE, original));
    assertEquals(original, roundTrip(TextCodec.JSON_ESCAPE, TextCodec.JSON_UNESCAPE, original));
  }

  private static String roundTrip(TextCodec encode, TextCodec decode, String text) {
    String encoded = convert(encode, text);
    if (encoded.equals(text)) {
      fail(encode + " changed nothing");
    }
    return convert(decode, encoded);
  }

  @Test
  void aTokenCanceledMidRunStopsEveryConversion() {
    String text = "a".repeat(3 * TextCodec.CHECK_CHARS);
    for (final TextCodec codec : TextCodec.values()) {
      final CancellationToken token = new CancellationToken();
      final CharSequence canceling = new CancelingText(text, token);
      assertThrows(
          CancellationException.class,
          new Executable() {
            @Override
            public void execute() throws CodecException {
              codec.convert(canceling, token);
            }
          },
          codec.name());
    }
  }

  /** A text that cancels a token when its char past the first check interval is read. */
  private static final class CancelingText implements CharSequence {
    private final String text;
    private final CancellationToken token;

    CancelingText(String text, CancellationToken token) {
      this.text = text;
      this.token = token;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      if (index == TextCodec.CHECK_CHARS + 1) {
        token.cancel();
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text;
    }
  }
}
