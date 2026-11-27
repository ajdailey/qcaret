package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import java.util.Locale;

/** The text of hex views: Go to Offset input, the status line and Copy as Hex. */
final class HexOffsetText {
  private HexOffsetText() {}

  /**
   * Parses Go to Offset input: decimal, or hex after {@code 0x}; a leading {@code +} or {@code -}
   * moves from the caret. Spaces around the text are ignored.
   *
   * @param text the input
   * @param caret the caret
   * @param size the source size
   * @return the offset from 0 to {@code size}, or -1 when the text does not parse or the offset is
   *     outside the source
   */
  static long parse(String text, long caret, long size) {
    String s = text.strip();
    int sign = 0;
    if (s.startsWith("+") || s.startsWith("-")) {
      sign = s.charAt(0) == '+' ? 1 : -1;
      s = s.substring(1).strip();
    }
    int radix = 10;
    if (s.length() > 2 && (s.startsWith("0x") || s.startsWith("0X"))) {
      radix = 16;
      s = s.substring(2);
    }
    if (s.isEmpty()) {
      return -1;
    }
    for (int i = 0; i < s.length(); i++) {
      if (Character.digit(s.charAt(i), radix) < 0) {
        return -1;
      }
    }
    long value;
    try {
      value = Long.parseLong(s, radix);
    } catch (NumberFormatException e) {
      return -1;
    }
    long offset = sign == 0 ? value : caret + sign * value;
    return offset >= 0 && offset <= size ? offset : -1;
  }

  /**
   * Formats an offset as the status line shows it.
   *
   * @param rows the layout, which sets the hex digits
   * @param offset the offset
   * @return for example {@code 0x0000002E (46)}
   */
  static String offset(HexRows rows, long offset) {
    return UiText.HEX_OFFSET.format(rows.offsetText(offset), offset);
  }

  /**
   * Formats the status line.
   *
   * @param rows the layout
   * @param selection the caret and selection
   * @return the caret offset, the selection's byte count when there is one, and the size
   */
  static String status(HexRows rows, HexSelection selection) {
    String caret = offset(rows, selection.caret());
    String size = offset(rows, rows.size());
    if (selection.length() == 0) {
      return UiText.HEX_STATUS.format(caret, size);
    }
    return UiText.HEX_STATUS_SELECTION.format(
        caret, String.format(Locale.ROOT, "%,d", selection.length()), size);
  }

  /**
   * Formats bytes as Copy as Hex puts them on the clipboard.
   *
   * @param bytes the bytes
   * @param length the count from the start
   * @return space-separated pairs, such as {@code 4D 5A 90 00}
   */
  static String hexPairs(byte[] bytes, int length) {
    StringBuilder b = new StringBuilder(Math.max(0, length * 3 - 1));
    for (int i = 0; i < length; i++) {
      if (i > 0) {
        b.append(' ');
      }
      b.append(HexRows.hexCell(bytes[i]));
    }
    return b.toString();
  }
}
