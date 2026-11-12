package io.github.ajdailey.qcaret.core.io;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A checksum list in the {@code sha256sum} format, one {@code <hex> <marker><name>} line per file:
 * the marker is a space, or {@code *} for a binary-mode line. The algorithm follows from the hex
 * length (32, 40, 64 or 128 digits; either case). A line starting with {@code \} carries a name
 * escaped as GNU coreutils writes it ({@code \\}, {@code \n}, {@code \r}). A line ends at LF, CR LF
 * or CR, and one leading U+FEFF is skipped. Blank lines are skipped; any other line that does not
 * parse is counted as bad.
 *
 * @param entries the parsed lines, in list order
 * @param badCount the lines that did not parse
 * @param badLines the first {@value #BAD_LINES_KEPT} of those, as 1-based line numbers
 */
public record ChecksumList(List<Entry> entries, int badCount, List<Integer> badLines) {
  /** Bad line numbers kept for display. */
  public static final int BAD_LINES_KEPT = 1000;

  /** Longer lines count as bad. */
  static final int MAX_LINE_CHARS = 64 * 1024;

  /**
   * Creates a list.
   *
   * @param entries the parsed lines
   * @param badCount the lines that did not parse
   * @param badLines the kept bad line numbers
   */
  public ChecksumList {
    entries = List.copyOf(entries);
    badLines = List.copyOf(badLines);
  }

  /**
   * One parsed line.
   *
   * @param line the 1-based line number
   * @param algorithm follows from the hex length
   * @param hex the expected digest in lower case
   * @param name the file name as written, unescaped
   * @param binary true for a {@code *} marker
   */
  public record Entry(
      int line, ChecksumAlgorithm algorithm, String hex, String name, boolean binary) {}

  /**
   * Formats one line as {@code sha256sum} writes it in text mode.
   *
   * @param hex the digest in lower case
   * @param name the file name; a backslash, LF or CR in it is escaped and the line starts with
   *     {@code \}
   * @return the line without a line break
   */
  public static String line(String hex, String name) {
    if (name.indexOf('\\') < 0 && name.indexOf('\n') < 0 && name.indexOf('\r') < 0) {
      return hex + "  " + name;
    }
    StringBuilder out = new StringBuilder(name.length() + hex.length() + 4);
    out.append('\\').append(hex).append("  ");
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  /**
   * Parses a whole text.
   *
   * @param text the list
   * @return the parsed list
   */
  public static ChecksumList parse(CharSequence text) {
    Parser parser = new Parser();
    parser.add(text, 0, text.length());
    return parser.finish();
  }

  /**
   * Parses one line.
   *
   * @param text the line without its line break
   * @param number the 1-based line number
   * @return the entry, or null when the line does not parse
   */
  static Entry parseLine(String text, int number) {
    boolean escaped = text.startsWith("\\");
    int start = escaped ? 1 : 0;
    int end = start;
    while (end < text.length() && hexDigit(text.charAt(end))) {
      end++;
    }
    ChecksumAlgorithm algorithm = ChecksumAlgorithm.forHexLength(end - start);
    if (algorithm == null || text.length() < end + 3) {
      return null;
    }
    char marker = text.charAt(end + 1);
    if (text.charAt(end) != ' ' || marker != ' ' && marker != '*') {
      return null;
    }
    String name = escaped ? unescape(text.substring(end + 2)) : text.substring(end + 2);
    if (name == null || name.isEmpty()) {
      return null;
    }
    String hex = text.substring(start, end).toLowerCase(Locale.ROOT);
    return new Entry(number, algorithm, hex, name, marker == '*');
  }

  private static boolean hexDigit(char c) {
    return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
  }

  /** Returns the name with its escapes undone, or null for an escape that is not defined. */
  private static String unescape(String name) {
    StringBuilder out = new StringBuilder(name.length());
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (++i == name.length()) {
        return null;
      }
      switch (name.charAt(i)) {
        case '\\' -> out.append('\\');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        default -> {
          return null;
        }
      }
    }
    return out.toString();
  }

  /**
   * Parses consecutive pieces of a list. A CR LF pair split between two pieces is one line break.
   * Use once; not thread-safe.
   */
  public static final class Parser {
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<Integer> badLines = new ArrayList<Integer>();
    private final StringBuilder line = new StringBuilder();
    private int number;
    private int badCount;
    private boolean overlong;
    private boolean started;
    private boolean afterCr;

    /**
     * Parses the next piece.
     *
     * @param text holds the piece
     * @param start the piece's first index
     * @param end the index after its last char
     */
    public void add(CharSequence text, int start, int end) {
      for (int i = start; i < end; i++) {
        char c = text.charAt(i);
        if (!started) {
          started = true;
          if (c == '\uFEFF') {
            continue;
          }
        }
        if (afterCr) {
          afterCr = false;
          if (c == '\n') {
            continue;
          }
        }
        if (c == '\n') {
          endLine();
        } else if (c == '\r') {
          endLine();
          afterCr = true;
        } else if (line.length() < MAX_LINE_CHARS) {
          line.append(c);
        } else {
          overlong = true;
        }
      }
    }

    /**
     * Parses the last line and returns the list.
     *
     * @return the parsed list
     */
    public ChecksumList finish() {
      if (line.length() > 0 || overlong) {
        endLine();
      }
      return new ChecksumList(entries, badCount, badLines);
    }

    private void endLine() {
      number++;
      String text = line.toString();
      line.setLength(0);
      boolean wasOverlong = overlong;
      overlong = false;
      if (!wasOverlong && text.isBlank()) {
        return;
      }
      Entry entry = wasOverlong ? null : parseLine(text, number);
      if (entry != null) {
        entries.add(entry);
        return;
      }
      badCount++;
      if (badLines.size() < BAD_LINES_KEPT) {
        badLines.add(number);
      }
    }
  }
}
