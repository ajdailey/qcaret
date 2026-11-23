package io.github.ajdailey.qcaret.core.format;

/**
 * Finds the first content of a scanned XML range that {@link XmlLayout} keeps as written though it
 * holds an element, comment or PI: mixed content or {@code xml:space="preserve"}. Content holding
 * only text and CDATA is not reported, since laying it out would change nothing.
 */
final class XmlKept {
  private final String s;
  private final int count;
  private final int[] starts;
  private final int[] ends;
  private final byte[] types;
  private final byte[] flags;

  private XmlKept(String s, int count, int[] starts, int[] ends, byte[] types, byte[] flags) {
    this.s = s;
    this.count = count;
    this.starts = starts;
    this.ends = ends;
    this.types = types;
    this.flags = flags;
  }

  /**
   * Returns the first kept content, with its offset in the range and line 0.
   *
   * @param s the range's text
   * @param count the number of items
   * @param starts each item's offset
   * @param ends the offset after each item
   * @param types each item's type
   * @param flags each start tag's {@link XmlLayout#MIXED} and {@link XmlLayout#PRESERVE} flags
   * @param topMixed true when the range's top level holds text or CDATA
   * @return the content, or null
   */
  static KeptContent find(
      String s, int count, int[] starts, int[] ends, byte[] types, byte[] flags, boolean topMixed) {
    XmlKept kept = new XmlKept(s, count, starts, ends, types, flags);
    if (topMixed && kept.holdsItem(0)) {
      return kept.describe(-1);
    }
    for (int k = 0; k < count; k++) {
      if (types[k] == XmlLayout.START && flags[k] != 0 && kept.holdsItem(k + 1)) {
        return kept.describe(k);
      }
    }
    return null;
  }

  /**
   * Reports whether the content starting at item {@code from} holds an element, comment or PI.
   * Mixed content holds text items only until its first other item, so the scans of every start tag
   * together stay linear.
   */
  private boolean holdsItem(int from) {
    for (int k = from; k < count; k++) {
      byte type = types[k];
      if (type == XmlLayout.START || type == XmlLayout.EMPTY || type == XmlLayout.OTHER) {
        return true;
      }
      if (type == XmlLayout.END) {
        return false;
      }
    }
    return false;
  }

  /** Describes the content of start tag {@code k}, or of the top level when k is -1. */
  private KeptContent describe(int k) {
    int at = -1;
    boolean text = false;
    boolean visible = false;
    int character = -1;
    int depth = 0;
    for (int i = k + 1; i < count; i++) {
      byte type = types[i];
      if (type == XmlLayout.END) {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (type == XmlLayout.START) {
        depth++;
      } else if (depth == 0 && (type == XmlLayout.TEXT || type == XmlLayout.CDATA)) {
        at = at < 0 ? starts[i] : at;
        if (type == XmlLayout.TEXT) {
          text = true;
          int j = starts[i];
          while (j < ends[i]) {
            int c = s.codePointAt(j);
            j += Character.charCount(c);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
              continue;
            }
            if (!invisible(c)) {
              visible = true;
            } else if (character < 0) {
              character = c;
            }
          }
        }
      }
    }
    KeptContent.Kind kind = text ? KeptContent.Kind.TEXT : KeptContent.Kind.CDATA;
    if (k >= 0 && (flags[k] & XmlLayout.PRESERVE) != 0) {
      kind = KeptContent.Kind.PRESERVE;
    }
    int shown = kind == KeptContent.Kind.TEXT && !visible ? character : -1;
    if (k < 0) {
      return new KeptContent(kind, null, at, 0, shown);
    }
    return new KeptContent(kind, name(starts[k] + 1), starts[k], 0, shown);
  }

  /** Returns the tag name starting at {@code from}. */
  private String name(int from) {
    int i = from;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '>' || c == '/') {
        break;
      }
      i++;
    }
    return s.substring(from, i);
  }

  /** Reports whether a char outside XML whitespace shows as a space or as nothing. */
  private static boolean invisible(int c) {
    int type = Character.getType(c);
    return Character.isWhitespace(c)
        || Character.isSpaceChar(c)
        || type == Character.FORMAT
        || type == Character.CONTROL;
  }
}
