package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CancellationException;

/**
 * Encode and decode conversions of a text. Base64 and URL forms carry the text's UTF-8 bytes; a
 * decode whose bytes are not valid UTF-8 is refused, never decoded with replacement chars. Each
 * conversion checks a {@link CancellationToken} every {@value #CHECK_CHARS} chars.
 */
public enum TextCodec {
  /** UTF-8 bytes to Base64, padded, on one line. */
  BASE64_ENCODE,
  /** Base64 to text; spaces, tabs, form feeds and line breaks are skipped, padding is optional. */
  BASE64_DECODE,
  /** RFC 3986 percent-encoding of UTF-8 bytes: everything but {@code A-Z a-z 0-9 - . _ ~}. */
  URL_ENCODE,
  /** Percent escapes to UTF-8 bytes; {@code +} stays {@code +}. */
  URL_DECODE,
  /** {@code & < > " '} to their predefined XML entities. */
  XML_ESCAPE,
  /** Predefined XML entities, HTML 4.01 named entities and character references to chars. */
  XML_UNESCAPE,
  /** The content of a JSON string, without quotes, with RFC 8259 escapes. */
  JSON_ESCAPE,
  /** RFC 8259 escapes in the content of a JSON string to chars. */
  JSON_UNESCAPE;

  /** Chars between cancellation checks. */
  static final int CHECK_CHARS = 65536;

  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  /**
   * Converts a text.
   *
   * @param text the text
   * @param token checked every {@value #CHECK_CHARS} chars
   * @return the converted text
   * @throws CodecException when the text is not valid input for the conversion
   * @throws CancellationException when canceled
   */
  public String convert(CharSequence text, CancellationToken token) throws CodecException {
    return switch (this) {
      case BASE64_ENCODE -> Base64.getEncoder().encodeToString(utf8(text, token));
      case BASE64_DECODE -> base64Decode(text, token);
      case URL_ENCODE -> urlEncode(text, token);
      case URL_DECODE -> urlDecode(text, token);
      case XML_ESCAPE -> XmlEntities.escape(text, token);
      case XML_UNESCAPE -> XmlEntities.unescape(text, token);
      case JSON_ESCAPE -> JsonStrings.escape(text, token);
      case JSON_UNESCAPE -> JsonStrings.unescape(text, token);
    };
  }

  /** Checks the token when {@code index} starts a new block of {@value #CHECK_CHARS}. */
  static void check(int index, CancellationToken token) {
    if (index % CHECK_CHARS == 0) {
      token.check();
    }
  }

  private static byte[] utf8(CharSequence text, CancellationToken token) throws CodecException {
    token.check();
    ByteBuffer bytes;
    try {
      bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(text));
    } catch (CharacterCodingException e) {
      throw new CodecException(CodecException.Reason.UNPAIRED_SURROGATE);
    }
    byte[] out = new byte[bytes.remaining()];
    bytes.get(out);
    token.check();
    return out;
  }

  private static String text(byte[] bytes, CancellationToken token) throws CodecException {
    token.check();
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new CodecException(CodecException.Reason.NOT_UTF8);
    }
  }

  private static String base64Decode(CharSequence text, CancellationToken token)
      throws CodecException {
    ByteArrayOutputStream ascii = new ByteArrayOutputStream(text.length());
    for (int i = 0; i < text.length(); i++) {
      check(i, token);
      char c = text.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
        continue;
      }
      if (c > 0x7f) {
        throw new CodecException(CodecException.Reason.NOT_BASE64);
      }
      ascii.write(c);
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(ascii.toByteArray());
    } catch (IllegalArgumentException e) {
      throw new CodecException(CodecException.Reason.NOT_BASE64);
    }
    return text(bytes, token);
  }

  private static String urlEncode(CharSequence text, CancellationToken token)
      throws CodecException {
    byte[] bytes = utf8(text, token);
    StringBuilder out = new StringBuilder(bytes.length);
    for (int i = 0; i < bytes.length; i++) {
      check(i, token);
      int b = bytes[i] & 0xff;
      if (b >= 'A' && b <= 'Z'
          || b >= 'a' && b <= 'z'
          || b >= '0' && b <= '9'
          || b == '-'
          || b == '.'
          || b == '_'
          || b == '~') {
        out.append((char) b);
      } else {
        out.append('%').append(HEX[b >> 4]).append(HEX[b & 0xf]);
      }
    }
    return out.toString();
  }

  private static String urlDecode(CharSequence text, CancellationToken token)
      throws CodecException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
    int i = 0;
    int checked = 0;
    while (i < text.length()) {
      if (i - checked >= CHECK_CHARS) {
        token.check();
        checked = i;
      }
      char c = text.charAt(i);
      if (c == '%') {
        int high = i + 2 < text.length() ? hex(text.charAt(i + 1)) : -1;
        int low = i + 2 < text.length() ? hex(text.charAt(i + 2)) : -1;
        if (high < 0 || low < 0) {
          throw new CodecException(CodecException.Reason.MALFORMED_ESCAPE);
        }
        bytes.write(high << 4 | low);
        i += 3;
      } else {
        int cp = Character.codePointAt(text, i);
        if (Character.isSurrogate((char) cp)) {
          throw new CodecException(CodecException.Reason.UNPAIRED_SURROGATE);
        }
        writeUtf8(bytes, cp);
        i += Character.charCount(cp);
      }
    }
    return text(bytes.toByteArray(), token);
  }

  /** Returns the value of an ASCII hex digit, or -1. */
  static int hex(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
  }

  private static void writeUtf8(ByteArrayOutputStream out, int cp) {
    if (cp < 0x80) {
      out.write(cp);
    } else if (cp < 0x800) {
      out.write(0xc0 | cp >> 6);
      out.write(0x80 | cp & 0x3f);
    } else if (cp < 0x10000) {
      out.write(0xe0 | cp >> 12);
      out.write(0x80 | cp >> 6 & 0x3f);
      out.write(0x80 | cp & 0x3f);
    } else {
      out.write(0xf0 | cp >> 18);
      out.write(0x80 | cp >> 12 & 0x3f);
      out.write(0x80 | cp >> 6 & 0x3f);
      out.write(0x80 | cp & 0x3f);
    }
  }
}
