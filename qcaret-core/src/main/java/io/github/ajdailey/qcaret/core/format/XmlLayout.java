package io.github.ajdailey.qcaret.core.format;

import java.util.Arrays;

/**
 * Pretty Print and Minify of a well-formed XML range ({@link XmlCheck} passed it). The range splits
 * into items: tags, comments, PIs, CDATA sections, the DOCTYPE and text runs. Only the gap between
 * two items of a laid-out content changes:
 *
 * <ul>
 *   <li>The top level and the content of an element are laid out unless the content holds
 *       non-whitespace text or CDATA (mixed content or text only), the element is inside such
 *       content (a fragment's top level counts), or {@code xml:space="preserve"} applies to it;
 *       {@code xml:space="default"} resets the inherited preserve.
 *   <li>Pretty Print puts each item of a laid-out content on its own line, indented one level per
 *       open element; an element with nothing but whitespace inside becomes {@code <a></a>}. Minify
 *       removes the gap.
 *   <li>Whitespace before the first item and after the last stays.
 * </ul>
 *
 * Both passes are iterative, so nesting depth is bounded by the heap.
 */
final class XmlLayout {
  static final byte TEXT = 0;
  static final byte SPACE = 1;
  static final byte START = 2;
  static final byte END = 3;
  static final byte EMPTY = 4;
  static final byte CDATA = 5;
  static final byte OTHER = 6;

  /** A start tag's content holds text or CDATA. */
  static final byte MIXED = 1;

  /** {@code xml:space="preserve"} applies to a start tag's element. */
  static final byte PRESERVE = 2;

  private final String s;
  private final Output out;
  private final boolean pretty;
  private int count;
  private int topElements;
  private boolean topMixed;
  private int[] starts = new int[64];
  private int[] ends = new int[64];
  private byte[] types = new byte[64];
  private byte[] flags = new byte[64];

  private XmlLayout(String source, Output out, boolean pretty) {
    this.s = source;
    this.out = out;
    this.pretty = pretty;
  }

  /**
   * Lays a well-formed range out into {@code out}.
   *
   * @param source the range's text
   * @param out receives the text
   * @param pretty true for Pretty Print, false for Minify
   * @return the first content kept as written ({@link XmlKept}), with its offset in the range and
   *     line 0; null when there is none
   */
  static KeptContent layout(String source, Output out, boolean pretty) {
    XmlLayout layout = new XmlLayout(source, out, pretty);
    layout.scan();
    layout.emit();
    return XmlKept.find(
        source,
        layout.count,
        layout.starts,
        layout.ends,
        layout.types,
        layout.flags,
        layout.topMixed);
  }

  /**
   * Reports whether a range holds one element and no text or CDATA at its top level, so it parses
   * as a document; anything else, including a range the scan cannot split, is a fragment.
   *
   * @param source the range's text
   * @return true for one top-level element
   */
  static boolean singleRoot(String source) {
    XmlLayout layout = new XmlLayout(source, null, false);
    try {
      layout.scan();
    } catch (FormatException e) {
      return false;
    }
    return layout.topElements == 1 && !layout.topMixed;
  }

  /**
   * The items of a range, in text order and covering it from offset 0.
   *
   * @param count the number of items
   * @param starts each item's offset
   * @param ends the offset after each item
   * @param types each item's type: {@link #TEXT}, {@link #SPACE}, {@link #START}, {@link #END},
   *     {@link #EMPTY}, {@link #CDATA} or {@link #OTHER}
   * @param complete false when an unterminated construct ended the split early
   */
  record Items(int count, int[] starts, int[] ends, byte[] types, boolean complete) {}

  /**
   * Splits a range that need not be well-formed into items, up to the first construct that does not
   * end.
   *
   * @param source the range's text
   * @return the items
   */
  static Items items(String source) {
    XmlLayout layout = new XmlLayout(source, null, false);
    boolean complete = true;
    try {
      layout.scan();
    } catch (FormatException e) {
      complete = false;
    }
    return new Items(layout.count, layout.starts, layout.ends, layout.types, complete);
  }

  /** Splits the range into items and flags each start tag. */
  private void scan() {
    int n = s.length();
    int[] open = new int[16];
    int depth = 0;
    int i = 0;
    while (i < n) {
      int end;
      byte type;
      if (s.charAt(i) != '<') {
        end = s.indexOf('<', i);
        end = end < 0 ? n : end;
        type = blank(i, end) ? SPACE : TEXT;
      } else if (s.startsWith("<!--", i)) {
        end = after("-->", i + 4);
        type = OTHER;
      } else if (s.startsWith("<![CDATA[", i)) {
        end = after("]]>", i + 9);
        type = CDATA;
      } else if (s.startsWith("<?", i)) {
        end = after("?>", i + 2);
        type = OTHER;
      } else if (s.startsWith("<!", i)) {
        end = doctypeEnd(i + 2);
        type = OTHER;
      } else if (s.startsWith("</", i)) {
        end = after(">", i + 2);
        type = END;
      } else {
        end = tagEnd(i + 1);
        type = s.charAt(end - 2) == '/' ? EMPTY : START;
      }
      add(i, end, type);
      if (depth == 0 && (type == START || type == EMPTY)) {
        topElements++;
      }
      if ((type == TEXT || type == CDATA) && depth == 0) {
        topMixed = true;
      } else if (type == TEXT || type == CDATA) {
        flags[open[depth - 1]] |= MIXED;
      } else if (type == START || type == EMPTY) {
        boolean inherited = depth > 0 && (flags[open[depth - 1]] & PRESERVE) != 0;
        String space = xmlSpace(i + 1, end);
        boolean preserve = space == null ? inherited : space.equals("preserve");
        if (preserve) {
          flags[count - 1] |= PRESERVE;
        }
        if (type == START) {
          if (depth == open.length) {
            open = Arrays.copyOf(open, depth * 2);
          }
          open[depth++] = count - 1;
        }
      } else if (type == END && depth > 0) {
        depth--;
      }
      i = end;
    }
  }

  /** Writes the items, replacing the gaps of laid-out content. */
  private void emit() {
    // Per open element: whether its content is laid out, and whether it or an ancestor is mixed.
    boolean[] laidOut = new boolean[16];
    boolean[] inMixed = new boolean[16];
    int depth = 0;
    boolean topLaidOut = !topMixed;
    int pos = 0;
    boolean first = true;
    byte previous = SPACE;
    for (int k = 0; k < count; k++) {
      byte type = types[k];
      if (type == SPACE) {
        continue;
      }
      int start = starts[k];
      boolean context = depth == 0 ? topLaidOut : laidOut[depth - 1];
      if (first) {
        out.copy(pos, start);
        first = false;
      } else if (!context) {
        out.copy(pos, start);
      } else if (type == END && previous == START) {
        out.join(pos, start);
      } else if (pretty) {
        out.newline(pos, start, type == END ? depth - 1 : depth);
      } else {
        out.join(pos, start);
      }
      out.copy(start, ends[k]);
      pos = ends[k];
      previous = type;
      if (type == START) {
        boolean mixedAbove = depth > 0 ? inMixed[depth - 1] : topMixed;
        if (depth == laidOut.length) {
          laidOut = Arrays.copyOf(laidOut, depth * 2);
          inMixed = Arrays.copyOf(inMixed, depth * 2);
        }
        inMixed[depth] = mixedAbove || (flags[k] & MIXED) != 0;
        laidOut[depth] = !inMixed[depth] && (flags[k] & PRESERVE) == 0;
        depth++;
      } else if (type == END && depth > 0) {
        depth--;
      }
    }
    out.copy(pos, s.length());
  }

  private void add(int start, int end, byte type) {
    if (count == starts.length) {
      int size = count * 2;
      starts = Arrays.copyOf(starts, size);
      ends = Arrays.copyOf(ends, size);
      types = Arrays.copyOf(types, size);
      flags = Arrays.copyOf(flags, size);
    }
    starts[count] = start;
    ends[count] = end;
    types[count] = type;
    flags[count] = 0;
    count++;
  }

  private boolean blank(int from, int to) {
    for (int i = from; i < to; i++) {
      char c = s.charAt(i);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return false;
      }
    }
    return true;
  }

  private int after(String close, int from) {
    int at = s.indexOf(close, from);
    if (at < 0) {
      throw new FormatException(from, "Unexpected end of the XML text");
    }
    return at + close.length();
  }

  private int skipQuoted(int at) {
    return after(String.valueOf(s.charAt(at)), at + 1);
  }

  /** Returns the offset after a start or empty tag whose name starts at {@code at}. */
  private int tagEnd(int at) {
    int i = at;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipQuoted(i);
      } else if (c == '>') {
        return i + 1;
      } else {
        i++;
      }
    }
    throw new FormatException(at, "Unexpected end of the XML text");
  }

  /** Returns the offset after a DOCTYPE whose keyword starts at {@code at}. */
  private int doctypeEnd(int at) {
    boolean subset = false;
    int i = at;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"' || c == '\'') {
        i = skipQuoted(i);
      } else if (subset && s.startsWith("<!--", i)) {
        i = after("-->", i + 4);
      } else if (subset && s.startsWith("<?", i)) {
        i = after("?>", i + 2);
      } else if (c == '[') {
        subset = true;
        i++;
      } else if (c == ']') {
        subset = false;
        i++;
      } else if (c == '>' && !subset) {
        return i + 1;
      } else {
        i++;
      }
    }
    throw new FormatException(at, "Unexpected end of the XML text");
  }

  /**
   * Returns the value of the tag's {@code xml:space} attribute, or null when it has none or another
   * value.
   */
  private String xmlSpace(int from, int to) {
    int i = from;
    while (i < to && !isSpace(s.charAt(i)) && s.charAt(i) != '>' && s.charAt(i) != '/') {
      i++;
    }
    while (i < to) {
      while (i < to && isSpace(s.charAt(i))) {
        i++;
      }
      int nameStart = i;
      while (i < to && !isSpace(s.charAt(i)) && s.charAt(i) != '=') {
        if (s.charAt(i) == '>' || s.charAt(i) == '/') {
          return null;
        }
        i++;
      }
      String name = s.substring(nameStart, i);
      while (i < to && s.charAt(i) != '"' && s.charAt(i) != '\'') {
        i++;
      }
      if (i >= to) {
        return null;
      }
      int valueEnd = s.indexOf(s.charAt(i), i + 1);
      String value = s.substring(i + 1, valueEnd);
      i = valueEnd + 1;
      if (name.equals("xml:space")) {
        return value.equals("preserve") || value.equals("default") ? value : null;
      }
    }
    return null;
  }

  private static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r';
  }
}
