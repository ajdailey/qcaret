package io.github.ajdailey.qcaret.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * An EditorConfig section name compiled to a regular expression over a file's path relative to the
 * {@code .editorconfig} file's directory, written with {@code /} and a leading {@code /}.
 *
 * <table>
 *   <caption>Glob syntax</caption>
 *   <tr><th>Glob</th><th>Matches</th></tr>
 *   <tr><td>{@code *}</td><td>Any chars but {@code /}</td></tr>
 *   <tr><td>{@code **}</td><td>Any chars</td></tr>
 *   <tr><td>{@code ?}</td><td>One char but {@code /}</td></tr>
 *   <tr><td>{@code [abc]}, {@code [a-c]}, {@code [!abc]}</td><td>One char of the set, or not of
 *       it; a {@code [} whose set holds a {@code /} or never closes is literal</td></tr>
 *   <tr><td>{@code {s1,s2}}</td><td>Any of the comma-separated globs, nested; literal when the
 *       pattern's braces do not balance</td></tr>
 *   <tr><td>{@code {n1..n2}}</td><td>An integer from n1 to n2 without a leading zero</td></tr>
 *   <tr><td>{@code \c}</td><td>The char {@code c}</td></tr>
 * </table>
 *
 * <p>A glob without a {@code /} matches the file name at any depth; one with a {@code /} is
 * anchored at the {@code .editorconfig} file's directory, and a leading {@code /} is dropped.
 *
 * <p>A section whose expression does not compile, or whose range bound exceeds a {@code long}, is
 * not {@link #valid} and matches nothing. A match reads the path through a {@link BoundedChars}, so
 * a backtracking expression ends at the read limit as {@link Match#LIMIT}.
 */
public final class EditorConfigGlob {
  private static final Pattern NUMERIC = Pattern.compile("([+-]?\\d+)\\.\\.([+-]?\\d+)");

  /** The outcome of {@link #match}. */
  public enum Match {
    /** The path matches. */
    YES,
    /** The path does not match, or the glob is not valid. */
    NO,
    /** The match reached the read limit or overflowed the stack. */
    LIMIT
  }

  private final String section;
  private final Pattern pattern;
  private final List<long[]> ranges;

  private EditorConfigGlob(String section, Pattern pattern, List<long[]> ranges) {
    this.section = section;
    this.pattern = pattern;
    this.ranges = ranges;
  }

  /**
   * Compiles a section name; a name whose expression does not compile gives a glob that is not
   * {@link #valid}.
   *
   * @param section the text between the section's brackets
   * @return the glob
   */
  public static EditorConfigGlob compile(String section) {
    String glob;
    if (hasSlash(section)) {
      glob = section.startsWith("/") ? section : "/" + section;
    } else {
      glob = "**/" + section;
    }
    List<long[]> ranges = new ArrayList<long[]>();
    Pattern pattern;
    try {
      pattern = Pattern.compile(translate(glob, ranges, balanced(glob)), Pattern.DOTALL);
    } catch (PatternSyntaxException | NumberFormatException | StackOverflowError e) {
      pattern = null;
    }
    return new EditorConfigGlob(section, pattern, ranges);
  }

  /**
   * Returns the section name.
   *
   * @return the text between the section's brackets
   */
  public String section() {
    return section;
  }

  /**
   * Reports whether the section name compiled.
   *
   * @return false for a glob that matches nothing because its expression does not compile
   */
  public boolean valid() {
    return pattern != null;
  }

  /**
   * Reports whether a path matches within a read limit of {@link BoundedChars#lineLimit}; a match
   * that reaches the limit is false.
   *
   * @param path the path relative to the {@code .editorconfig} file's directory, with a leading
   *     {@code /} and {@code /} between names
   * @return true when it matches
   */
  public boolean matches(String path) {
    return match(new BoundedChars().reset(path, BoundedChars.lineLimit(path))) == Match.YES;
  }

  /**
   * Matches a path read through a bounded sequence.
   *
   * @param path the path as {@link #matches} takes it, with the read limit for this match
   * @return the outcome; {@link Match#NO} for a glob that is not {@link #valid}
   */
  public Match match(BoundedChars path) {
    if (pattern == null) {
      return Match.NO;
    }
    try {
      return matchesInRange(pattern.matcher(path)) ? Match.YES : Match.NO;
    } catch (BoundedChars.LimitReached | StackOverflowError e) {
      return Match.LIMIT;
    }
  }

  private boolean matchesInRange(Matcher m) {
    if (!m.matches()) {
      return false;
    }
    for (int i = 0; i < ranges.size(); i++) {
      String number = m.group(i + 1);
      String digits =
          number.startsWith("+") || number.startsWith("-") ? number.substring(1) : number;
      if (digits.length() > 1 && digits.charAt(0) == '0') {
        return false;
      }
      long value;
      try {
        value = Long.parseLong(number);
      } catch (NumberFormatException e) {
        return false;
      }
      long[] range = ranges.get(i);
      if (value < Math.min(range[0], range[1]) || value > Math.max(range[0], range[1])) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasSlash(String glob) {
    boolean escaped = false;
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '/') {
        return true;
      }
    }
    return false;
  }

  /** Reports whether the unescaped opening and closing braces are as many. */
  private static boolean balanced(String glob) {
    int open = 0;
    int close = 0;
    boolean escaped = false;
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '{') {
        open++;
      } else if (c == '}') {
        close++;
      }
    }
    return open == close;
  }

  private static String translate(String glob, List<long[]> ranges, boolean balanced) {
    StringBuilder out = new StringBuilder(glob.length() * 2);
    int length = glob.length();
    int braces = 0;
    boolean inSet = false;
    boolean escaped = false;
    int i = 0;
    while (i < length) {
      char c = glob.charAt(i++);
      if (escaped) {
        escaped = false;
        literal(out, c);
        continue;
      }
      if (c == '\\') {
        escaped = true;
        continue;
      }
      if (inSet) {
        if (c == ']') {
          out.append(']');
          inSet = false;
        } else if (c == '-') {
          out.append('-');
        } else {
          literal(out, c);
        }
        continue;
      }
      switch (c) {
        case '*' -> {
          if (i < length && glob.charAt(i) == '*') {
            out.append(".*");
            i++;
          } else {
            out.append("[^/]*");
          }
        }
        case '?' -> out.append("[^/]");
        case '[' -> {
          if (setEnd(glob, i) < 0) {
            literal(out, c);
          } else {
            inSet = true;
            out.append('[');
            if (i < length && (glob.charAt(i) == '!' || glob.charAt(i) == '^')) {
              out.append('^');
              i++;
            }
          }
        }
        case '{' -> {
          int end = braceEnd(glob, i);
          if (end >= 0) {
            String inner = glob.substring(i, end);
            Matcher numeric = NUMERIC.matcher(inner);
            if (numeric.matches()) {
              ranges.add(
                  new long[] {Long.parseLong(numeric.group(1)), Long.parseLong(numeric.group(2))});
              out.append("([+-]?\\d+)");
            } else {
              out.append("\\{").append(translate(inner, ranges, balanced(inner))).append("\\}");
            }
            i = end + 1;
          } else if (balanced) {
            out.append("(?:");
            braces++;
          } else {
            literal(out, c);
          }
        }
        case ',' -> {
          if (braces > 0) {
            out.append('|');
          } else {
            literal(out, c);
          }
        }
        case '}' -> {
          if (braces > 0) {
            out.append(')');
            braces--;
          } else {
            literal(out, c);
          }
        }
        case '/' -> {
          if (glob.startsWith("**/", i)) {
            out.append("(?:/|/.*/)");
            i += 3;
          } else {
            out.append('/');
          }
        }
        default -> literal(out, c);
      }
    }
    return out.toString();
  }

  /**
   * Returns the index of the {@code ]} closing a set that starts at {@code from}, or -1 when the
   * set holds a {@code /} or does not close.
   */
  private static int setEnd(String glob, int from) {
    boolean escaped = false;
    for (int i = from; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '/') {
        return -1;
      } else if (c == ']') {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the index of the {@code }} closing a brace opened before {@code from} when no comma
   * comes first, else -1: such a brace holds one glob or a numeric range.
   */
  private static int braceEnd(String glob, int from) {
    boolean escaped = false;
    for (int i = from; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == ',') {
        return -1;
      } else if (c == '}') {
        return i;
      }
    }
    return -1;
  }

  private static void literal(StringBuilder out, char c) {
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c > 127) {
      out.append(c);
    } else {
      out.append('\\').append(c);
    }
  }
}
