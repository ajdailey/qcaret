package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EncodingDetectorTest {
  private static final Charset CP1252 = Charset.forName("windows-1252");

  private static EncodingDetection detect(byte[] bytes) {
    return EncodingDetector.detect(bytes, bytes.length, CP1252);
  }

  private static byte[] bytes(int... values) {
    byte[] out = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = (byte) values[i];
    }
    return out;
  }

  private static void assertDetected(
      String charset, boolean bom, DetectionReason reason, EncodingDetection detection) {
    assertEquals(charset, detection.encoding().charset().name());
    assertEquals(bom, detection.encoding().bom());
    assertEquals(reason, detection.reason());
  }

  @Test
  void byteOrderMarksIncludingUtf32() {
    assertDetected("UTF-8", true, DetectionReason.BOM, detect(bytes(0xef, 0xbb, 0xbf, 'a')));
    assertDetected("UTF-16LE", true, DetectionReason.BOM, detect(bytes(0xff, 0xfe, 'a', 0)));
    assertDetected("UTF-16BE", true, DetectionReason.BOM, detect(bytes(0xfe, 0xff, 0, 'a')));
    assertDetected(
        "UTF-32LE", true, DetectionReason.BOM, detect(bytes(0xff, 0xfe, 0, 0, 'a', 0, 0, 0)));
    assertDetected(
        "UTF-32BE", true, DetectionReason.BOM, detect(bytes(0, 0, 0xfe, 0xff, 0, 0, 0, 'a')));
  }

  @Test
  void aForcedUtf16TakesFfFe0000AsItsBomAndANul() {
    byte[] data = bytes(0xff, 0xfe, 0, 0, 'a', 0);
    EncodingDetection forced = EncodingDetector.forced(StandardCharsets.UTF_16, data, data.length);
    assertDetected("UTF-16LE", true, DetectionReason.FORCED, forced);
    EncodingDetection plain = EncodingDetector.forced(CP1252, data, data.length);
    assertDetected("windows-1252", false, DetectionReason.FORCED, plain);
  }

  @Test
  void utf16WithoutBomByItsZeroBytes() {
    assertDetected(
        "UTF-16LE",
        false,
        DetectionReason.UTF16_PATTERN,
        detect("plain text".getBytes(StandardCharsets.UTF_16LE)));
    assertDetected(
        "UTF-16BE",
        false,
        DetectionReason.UTF16_PATTERN,
        detect("plain text".getBytes(StandardCharsets.UTF_16BE)));
  }

  @Test
  void aDeclarationCountsOnlyWhenTheFileDecodesWithIt() {
    byte[] xml =
        "<?xml version=\"1.0\" encoding=\"ISO-8859-15\"?><a>é</a>"
            .getBytes(Charset.forName("ISO-8859-15"));
    assertDetected("ISO-8859-15", false, DetectionReason.DECLARED, detect(xml));
    byte[] html = "<html><meta charset=\"koi8-r\"><p>ж".getBytes(Charset.forName("KOI8-R"));
    assertDetected("KOI8-R", false, DetectionReason.DECLARED, detect(html));
    byte[] python =
        "#!/usr/bin/env python\n# -*- coding: latin-1 -*-\n".getBytes(StandardCharsets.ISO_8859_1);
    assertDetected("ISO-8859-1", false, DetectionReason.DECLARED, detect(python));
    byte[] wrong =
        "<?xml version=\"1.0\" encoding=\"US-ASCII\"?>é".getBytes(StandardCharsets.UTF_8);
    assertDetected("UTF-8", false, DetectionReason.UTF8_VALID, detect(wrong));
    byte[] unknown = "<?xml encoding=\"x-no-such\"?>".getBytes(StandardCharsets.US_ASCII);
    assertDetected("UTF-8", false, DetectionReason.UTF8_VALID, detect(unknown));
  }

  @Test
  void theFallbackYieldsToIso88591OnItsUndefinedBytes() {
    assertDetected("windows-1252", false, DetectionReason.FALLBACK, detect(bytes('a', 0x80)));
    assertDetected("ISO-8859-1", false, DetectionReason.FALLBACK, detect(bytes('a', 0x81)));
    assertDetected("UTF-8", false, DetectionReason.UTF8_VALID, detect(new byte[0]));
  }

  @Test
  void bomBytesMatchTheCharset() {
    assertArrayEquals(
        bytes(0, 0, 0xfe, 0xff), TextEncoding.of(Charset.forName("UTF-32"), true).bomBytes());
    assertArrayEquals(new byte[0], TextEncoding.of(StandardCharsets.UTF_8, false).bomBytes());
  }

  /**
   * PKG-4: the Encoding menu lists only charsets this Java has; the bundles check the same list.
   */
  @Test
  void everyMenuCharsetIsSupported() {
    for (String name : TextEncoding.MENU) {
      assertTrue(Charset.isSupported(name), name);
    }
  }

  /** SET-8: a remembered encoding this Java lacks reads as none. */
  @Test
  void anUnknownCharsetNameIsNull() {
    assertNull(TextEncoding.forName("x-qcaret-missing"));
    assertNull(TextEncoding.forName("bad name!"));
    assertEquals(StandardCharsets.UTF_16BE, TextEncoding.forName("UTF-16").charset());
  }

  @Test
  void lineEndingCountsAndTheDominantKind() {
    LineEndingStats stats = LineEndingStats.count("a\r\nb\nc\rd\r\n", 0, 10);
    assertEquals(new LineEndingStats(1, 2, 1, LineEnding.CRLF), stats);
    assertTrue(stats.isMixed());
    assertEquals(LineEnding.CRLF, stats.dominant(LineEnding.LF));
    LineEndingStats tie = LineEndingStats.count("a\rb\n", 0, 4);
    assertEquals(LineEnding.CR, tie.dominant(LineEnding.LF));
    assertEquals(LineEnding.LF, LineEndingStats.NONE.dominant(LineEnding.LF));
    assertEquals(new LineEndingStats(0, 0, 1, LineEnding.CR), LineEndingStats.count("a\r\n", 0, 2));
  }
}
