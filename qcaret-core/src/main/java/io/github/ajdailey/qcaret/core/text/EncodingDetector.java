package io.github.ajdailey.qcaret.core.text;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the encoding of a whole file. Order: a BOM (UTF-8, UTF-32 LE/BE, UTF-16 LE/BE), the
 * UTF-16 zero-byte pattern in the first {@value #SAMPLE_BYTES} bytes, a declared charset (XML
 * declaration, HTML meta charset, a Python or Emacs coding line in the first two lines) that
 * decodes the whole file strictly, valid UTF-8, then the fallback, or ISO-8859-1 when the fallback
 * does not decode the file strictly. ISO-8859-1 maps every byte. The UTF-32LE BOM {@code FF FE 00
 * 00} is checked before the UTF-16LE BOM it starts with, so a UTF-16LE file whose first char is
 * U+0000 is detected as UTF-32LE.
 *
 * <p>UTF-16 pattern: at least {@value #UTF16_ZERO_PERCENT}% of the sampled bytes at one parity are
 * zero and under {@value #UTF16_OTHER_PERCENT}% at the other; zeros at odd offsets mean
 * little-endian.
 */
public final class EncodingDetector {
  /** Bytes the UTF-16 pattern and the binary check read. */
  public static final int SAMPLE_BYTES = 64 * 1024;

  /** Least share of zero bytes, in percent, at the parity that marks UTF-16. */
  public static final int UTF16_ZERO_PERCENT = 30;

  /** Greatest share of zero bytes, in percent, at the other parity. */
  public static final int UTF16_OTHER_PERCENT = 5;

  /** Bytes searched for a declaration. */
  private static final int DECLARATION_BYTES = 1024;

  private static final int DECODE_CHARS = 8192;
  private static final Charset UTF_32BE = Charset.forName("UTF-32BE");
  private static final Charset UTF_32LE = Charset.forName("UTF-32LE");
  private static final Pattern XML =
      Pattern.compile("^<\\?xml[^>]{0,200}?encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]{1,40})[\"']");
  private static final Pattern HTML =
      Pattern.compile(
          "<meta[^>]{0,200}?charset\\s*=\\s*[\"']?([A-Za-z0-9._:-]{1,40})",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CODING = Pattern.compile("coding[:=]\\s*([-\\w.]{1,40})");

  private EncodingDetector() {}

  /**
   * Detects the encoding of a file.
   *
   * @param bytes the file content in {@code [0, length)}; not modified
   * @param length the file length
   * @param fallback used when no other rule matches
   * @return the encoding and the rule that chose it
   */
  public static EncodingDetection detect(byte[] bytes, int length, Charset fallback) {
    TextEncoding bom = bom(bytes, length);
    if (bom != null) {
      return new EncodingDetection(bom, DetectionReason.BOM);
    }
    TextEncoding utf16 = utf16Pattern(bytes, Math.min(length, SAMPLE_BYTES));
    if (utf16 != null) {
      return new EncodingDetection(utf16, DetectionReason.UTF16_PATTERN);
    }
    Charset declared = declared(bytes, length);
    if (declared != null && decodes(declared, bytes, 0, length)) {
      return new EncodingDetection(TextEncoding.of(declared, false), DetectionReason.DECLARED);
    }
    if (decodes(StandardCharsets.UTF_8, bytes, 0, length)) {
      return new EncodingDetection(
          TextEncoding.of(StandardCharsets.UTF_8, false), DetectionReason.UTF8_VALID);
    }
    Charset chosen = decodes(fallback, bytes, 0, length) ? fallback : StandardCharsets.ISO_8859_1;
    return new EncodingDetection(TextEncoding.of(chosen, false), DetectionReason.FALLBACK);
  }

  /**
   * Applies a charset the user chose. A BOM for that charset is kept as the file's BOM; a
   * BOM-sensing {@code UTF-16} or {@code UTF-32} follows the BOM's byte order. For a UTF-16 charset
   * only the 2-byte BOMs count, so {@code FF FE 00 00} is a UTF-16LE BOM and a U+0000.
   *
   * @param charset the chosen charset
   * @param bytes the file content in {@code [0, length)}
   * @param length the file length
   * @return the encoding, reason {@link DetectionReason#FORCED}
   */
  public static EncodingDetection forced(Charset charset, byte[] bytes, int length) {
    TextEncoding chosen = TextEncoding.of(charset, false);
    TextEncoding bom = chosen.unitWidth() == 2 ? utf16Bom(bytes, length) : bom(bytes, length);
    if (bom != null) {
      boolean sensing =
          (charset.equals(StandardCharsets.UTF_16) || charset.name().equals("UTF-32"))
              && bom.unitWidth() == chosen.unitWidth();
      if (sensing || bom.charset().equals(chosen.charset())) {
        chosen = bom;
      }
    }
    return new EncodingDetection(chosen, DetectionReason.FORCED);
  }

  /**
   * Reports whether {@code bytes[from, to)} decodes with {@code charset} without a malformed or
   * unmappable sequence.
   *
   * @param charset the charset
   * @param bytes the bytes
   * @param from the first index
   * @param to the index after the last byte
   * @return true when the strict decode succeeds
   */
  public static boolean decodes(Charset charset, byte[] bytes, int from, int to) {
    CharsetDecoder decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer in = ByteBuffer.wrap(bytes, from, to - from);
    CharBuffer out = CharBuffer.allocate(DECODE_CHARS);
    while (true) {
      CoderResult result = decoder.decode(in, out, true);
      if (result.isError()) {
        return false;
      }
      if (result.isUnderflow()) {
        break;
      }
      out.clear();
    }
    while (true) {
      CoderResult result = decoder.flush(out);
      if (result.isError()) {
        return false;
      }
      if (result.isUnderflow()) {
        return true;
      }
      out.clear();
    }
  }

  private static TextEncoding bom(byte[] b, int length) {
    if (length >= 3 && (b[0] & 0xff) == 0xef && (b[1] & 0xff) == 0xbb && (b[2] & 0xff) == 0xbf) {
      return new TextEncoding(StandardCharsets.UTF_8, true);
    }
    if (length >= 4) {
      if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe && b[2] == 0 && b[3] == 0) {
        return new TextEncoding(UTF_32LE, true);
      }
      if (b[0] == 0 && b[1] == 0 && (b[2] & 0xff) == 0xfe && (b[3] & 0xff) == 0xff) {
        return new TextEncoding(UTF_32BE, true);
      }
    }
    return utf16Bom(b, length);
  }

  private static TextEncoding utf16Bom(byte[] b, int length) {
    if (length >= 2 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xfe) {
      return new TextEncoding(StandardCharsets.UTF_16LE, true);
    }
    if (length >= 2 && (b[0] & 0xff) == 0xfe && (b[1] & 0xff) == 0xff) {
      return new TextEncoding(StandardCharsets.UTF_16BE, true);
    }
    return null;
  }

  private static TextEncoding utf16Pattern(byte[] b, int sample) {
    long units = sample / 2;
    if (units == 0) {
      return null;
    }
    long evenZeros = 0;
    long oddZeros = 0;
    for (int i = 0; i < units; i++) {
      if (b[2 * i] == 0) {
        evenZeros++;
      }
      if (b[2 * i + 1] == 0) {
        oddZeros++;
      }
    }
    if (oddZeros * 100 >= units * UTF16_ZERO_PERCENT
        && evenZeros * 100 < units * UTF16_OTHER_PERCENT) {
      return new TextEncoding(StandardCharsets.UTF_16LE, false);
    }
    if (evenZeros * 100 >= units * UTF16_ZERO_PERCENT
        && oddZeros * 100 < units * UTF16_OTHER_PERCENT) {
      return new TextEncoding(StandardCharsets.UTF_16BE, false);
    }
    return null;
  }

  /** Returns the charset an ASCII-compatible declaration names, or null. */
  private static Charset declared(byte[] bytes, int length) {
    String head =
        new String(bytes, 0, Math.min(length, DECLARATION_BYTES), StandardCharsets.ISO_8859_1);
    String name = null;
    Matcher xml = XML.matcher(head);
    Matcher html = HTML.matcher(head);
    if (xml.find()) {
      name = xml.group(1);
    } else if (html.find()) {
      name = html.group(1);
    } else {
      Matcher coding = CODING.matcher(firstLines(head, 2));
      if (coding.find()) {
        name = coding.group(1);
      }
    }
    if (name == null) {
      return null;
    }
    // Python spells some charsets with a hyphen Java lacks: latin-1 for latin1.
    if (!supported(name)) {
      name = name.replace("-", "");
      if (!supported(name)) {
        return null;
      }
    }
    Charset charset = Charset.forName(name);
    // A declaration readable as ASCII rules out the wide encodings it might name.
    if (charset.name().toUpperCase(Locale.ROOT).startsWith("UTF-16")
        || charset.name().toUpperCase(Locale.ROOT).startsWith("UTF-32")) {
      return null;
    }
    return charset;
  }

  private static boolean supported(String name) {
    try {
      return Charset.isSupported(name);
    } catch (IllegalCharsetNameException e) {
      return false;
    }
  }

  private static String firstLines(String head, int lines) {
    int end = 0;
    for (int i = 0; i < lines && end >= 0; i++) {
      int lf = head.indexOf('\n', end);
      int cr = head.indexOf('\r', end);
      int next = lf < 0 ? cr : cr < 0 ? lf : Math.min(lf, cr);
      end = next < 0 ? -1 : next + 1;
    }
    return end < 0 ? head : head.substring(0, end);
  }
}
