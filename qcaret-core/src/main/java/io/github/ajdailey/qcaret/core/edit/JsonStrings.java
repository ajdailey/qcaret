package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;

/**
 * RFC 8259 escapes of a JSON string's content, without the quotes. Escape writes {@code \"}, a
 * doubled backslash, {@code \b \f \n \r \t}, a four-digit escape for the other controls below
 * U+0020 and for a surrogate without its pair, and every other char as it is. Unescape reads the
 * same set plus {@code \/}; a surrogate escape counts only as half of an escaped pair.
 */
final class JsonStrings {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private JsonStrings() {}

  static String escape(CharSequence text, CancellationToken token) {
    StringBuilder out = new StringBuilder(text.length() + 16);
    for (int i = 0; i < text.length(); i++) {
      TextCodec.check(i, token);
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20 || Character.isSurrogate(c) && !paired(text, i)) {
            out.append('\\').append('u');
            out.append(HEX[c >> 12]).append(HEX[c >> 8 & 0xf]);
            out.append(HEX[c >> 4 & 0xf]).append(HEX[c & 0xf]);
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  static String unescape(CharSequence text, CancellationToken token) throws CodecException {
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    int checked = 0;
    while (i < text.length()) {
      if (i - checked >= TextCodec.CHECK_CHARS) {
        token.check();
        checked = i;
      }
      char c = text.charAt(i);
      if (c != '\\') {
        out.append(c);
        i++;
        continue;
      }
      if (i + 1 >= text.length()) {
        throw malformed();
      }
      char kind = text.charAt(i + 1);
      switch (kind) {
        case '"', '\\', '/' -> out.append(kind);
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          char unit = unit(text, i + 2);
          if (Character.isHighSurrogate(unit)) {
            char low = isEscape(text, i + 6) ? unit(text, i + 8) : 0;
            if (!Character.isLowSurrogate(low)) {
              throw new CodecException(CodecException.Reason.LONE_SURROGATE_ESCAPE);
            }
            out.append(unit).append(low);
            i += 12;
            continue;
          }
          if (Character.isLowSurrogate(unit)) {
            throw new CodecException(CodecException.Reason.LONE_SURROGATE_ESCAPE);
          }
          out.append(unit);
          i += 6;
          continue;
        }
        default -> throw malformed();
      }
      i += 2;
    }
    return out.toString();
  }

  /** Reports whether a surrogate at {@code i} forms a pair with its neighbor. */
  private static boolean paired(CharSequence text, int i) {
    char c = text.charAt(i);
    if (Character.isHighSurrogate(c)) {
      return i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
    }
    return i > 0 && Character.isHighSurrogate(text.charAt(i - 1));
  }

  /** Reports whether a backslash-u escape starts at {@code i}. */
  private static boolean isEscape(CharSequence text, int i) {
    return i + 1 < text.length() && text.charAt(i) == '\\' && text.charAt(i + 1) == 'u';
  }

  /** Reads the four hex digits at {@code from}. */
  private static char unit(CharSequence text, int from) throws CodecException {
    if (from + 4 > text.length()) {
      throw malformed();
    }
    int value = 0;
    for (int i = from; i < from + 4; i++) {
      int digit = TextCodec.hex(text.charAt(i));
      if (digit < 0) {
        throw malformed();
      }
      value = value << 4 | digit;
    }
    return (char) value;
  }

  private static CodecException malformed() {
    return new CodecException(CodecException.Reason.MALFORMED_ESCAPE);
  }
}
