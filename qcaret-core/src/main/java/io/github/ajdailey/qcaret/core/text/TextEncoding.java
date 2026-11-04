package io.github.ajdailey.qcaret.core.text;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A file's charset and whether a byte order mark precedes the text. The BOM is a property of the
 * file, not text: decoding skips it and saving writes it back.
 *
 * @param charset decodes and encodes the text; never a BOM-sensing {@code UTF-16} or {@code UTF-32}
 * @param bom true when the file starts with this charset's byte order mark
 */
public record TextEncoding(Charset charset, boolean bom) {
  private static final Charset UTF_32BE = Charset.forName("UTF-32BE");
  private static final Charset UTF_32LE = Charset.forName("UTF-32LE");

  /**
   * The Encoding menu's charsets, by name. The bundles' jlink runtimes must carry every one; the
   * distribution smoke test checks that they do.
   */
  public static final List<String> MENU =
      List.of(
          "UTF-8",
          "UTF-16LE",
          "UTF-16BE",
          "UTF-32LE",
          "UTF-32BE",
          "ISO-8859-1",
          "ISO-8859-15",
          "windows-1252",
          "US-ASCII",
          "windows-1250",
          "windows-1251",
          "KOI8-R",
          "GB18030",
          "GBK",
          "Big5",
          "Shift_JIS",
          "windows-31j",
          "EUC-JP",
          "EUC-KR");

  /** Checks the components. */
  public TextEncoding {
    if (charset.equals(StandardCharsets.UTF_16) || charset.name().equals("UTF-32")) {
      throw new IllegalArgumentException("BOM-sensing charset " + charset.name());
    }
    if (bom && bomBytes(charset).length == 0) {
      throw new IllegalArgumentException(charset.name() + " has no byte order mark");
    }
  }

  /**
   * Returns the encoding for a charset, taking BOM-sensing {@code UTF-16} and {@code UTF-32} as
   * their big-endian forms.
   *
   * @param charset the charset
   * @param bom whether a byte order mark precedes the text
   * @return the encoding
   */
  public static TextEncoding of(Charset charset, boolean bom) {
    Charset plain = charset;
    if (charset.equals(StandardCharsets.UTF_16)) {
      plain = StandardCharsets.UTF_16BE;
    } else if (charset.name().equals("UTF-32")) {
      plain = UTF_32BE;
    }
    return new TextEncoding(plain, bom);
  }

  /**
   * Looks up a charset by name, for a remembered or command-line encoding.
   *
   * @param name the charset name or alias
   * @return the encoding without a BOM, or null when the name is malformed or this Java lacks it
   */
  public static TextEncoding forName(String name) {
    try {
      if (!Charset.isSupported(name)) {
        return null;
      }
    } catch (IllegalCharsetNameException e) {
      return null;
    }
    return of(Charset.forName(name), false);
  }

  /**
   * Returns the byte order mark written before the text.
   *
   * @return the BOM bytes, empty when {@link #bom} is false
   */
  public byte[] bomBytes() {
    return bom ? bomBytes(charset) : new byte[0];
  }

  /**
   * Returns the bytes per code unit: 2 for UTF-16, 4 for UTF-32, else 1.
   *
   * @return the unit width
   */
  public int unitWidth() {
    if (charset.equals(StandardCharsets.UTF_16BE) || charset.equals(StandardCharsets.UTF_16LE)) {
      return 2;
    }
    return charset.equals(UTF_32BE) || charset.equals(UTF_32LE) ? 4 : 1;
  }

  private static byte[] bomBytes(Charset charset) {
    if (charset.equals(StandardCharsets.UTF_8)) {
      return new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf};
    }
    if (charset.equals(StandardCharsets.UTF_16BE)) {
      return new byte[] {(byte) 0xfe, (byte) 0xff};
    }
    if (charset.equals(StandardCharsets.UTF_16LE)) {
      return new byte[] {(byte) 0xff, (byte) 0xfe};
    }
    if (charset.equals(UTF_32BE)) {
      return new byte[] {0, 0, (byte) 0xfe, (byte) 0xff};
    }
    if (charset.equals(UTF_32LE)) {
      return new byte[] {(byte) 0xff, (byte) 0xfe, 0, 0};
    }
    return new byte[0];
  }
}
