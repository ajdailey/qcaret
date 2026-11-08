package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
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

  /** Each charset once, in rule order; a BOM or the UTF-16 pattern stands alone. */
  @Test
  void theCandidatesFollowTheRules() {
    byte[] declared =
        "<?xml version=\"1.0\" encoding=\"ISO-8859-2\"?>".getBytes(StandardCharsets.US_ASCII);
    List<EncodingDetection> chain =
        EncodingDetector.candidates(declared, declared.length, StandardCharsets.ISO_8859_1);
    assertEquals(3, chain.size());
    assertEquals(DetectionReason.DECLARED, chain.get(0).reason());
    assertEquals(DetectionReason.UTF8_VALID, chain.get(1).reason());
    assertEquals(StandardCharsets.ISO_8859_1, chain.get(2).encoding().charset());
    assertEquals(DetectionReason.FALLBACK, chain.get(2).reason());
    byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a'};
    assertEquals(1, EncodingDetector.candidates(bom, 4, StandardCharsets.UTF_8).size());
  }

  private static List<EncodingDetection> candidates(byte[] data, boolean tail, Charset configured) {
    String end = tail ? new String(data, StandardCharsets.ISO_8859_1) : null;
    return EncodingDetector.candidates(data, data.length, end, CP1252, configured);
  }

  @Test
  void aConfiguredCharsetComesAfterADeclaredOneAndBeforeUtf8() {
    byte[] data = "caf\u00e9\n".getBytes(StandardCharsets.UTF_8);
    List<EncodingDetection> c = candidates(data, false, StandardCharsets.ISO_8859_1);
    assertDetected("ISO-8859-1", false, DetectionReason.CONFIGURED, c.get(0));
    assertDetected("UTF-8", false, DetectionReason.UTF8_VALID, c.get(1));
    byte[] declared = "# coding: windows-1252\n".getBytes(StandardCharsets.US_ASCII);
    c = candidates(declared, false, StandardCharsets.ISO_8859_1);
    assertDetected("windows-1252", false, DetectionReason.DECLARED, c.get(0));
    assertDetected("ISO-8859-1", false, DetectionReason.CONFIGURED, c.get(1));
  }

  @Test
  void aByteOrderMarkWinsOverAConfiguredCharset() {
    byte[] data = bytes(0xef, 0xbb, 0xbf, 'a');
    List<EncodingDetection> c = candidates(data, true, StandardCharsets.ISO_8859_1);
    assertEquals(1, c.size());
    assertDetected("UTF-8", true, DetectionReason.BOM, c.get(0));
  }

  @Test
  void aModelineAtTheEndDeclaresOnlyWhenTheTailIsRead() {
    byte[] data =
        ("x\n".repeat(50) + "# vim: set fenc=latin1 :\n").getBytes(StandardCharsets.US_ASCII);
    assertDetected(
        "ISO-8859-1", false, DetectionReason.DECLARED, candidates(data, true, null).get(0));
    assertDetected(
        "UTF-8", false, DetectionReason.UTF8_VALID, candidates(data, false, null).get(0));
  }
}
