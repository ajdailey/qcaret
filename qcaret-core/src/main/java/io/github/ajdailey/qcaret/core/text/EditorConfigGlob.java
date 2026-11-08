package io.github.ajdailey.qcaret.core.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 */
public final class EditorConfigGlob {
  private static final Pattern NUMERIC = Pattern.compile("([+-]?\\d+)\\.\\.([+-]?\\d+)");

  private final Pattern pattern;
  private final List<long[]> ranges;

  private EditorConfigGlob(Pattern pattern, List<long[]> ranges) {
    this.pattern = pattern;
    this.ranges = ranges;
  }

  /**
   * Compiles a section name.
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
    String regex = translate(glob, ranges, balanced(glob));
    return new EditorConfigGlob(Pattern.compile(regex, Pattern.DOTALL), ranges);
  }

  /**
   * Reports whether a path matches.
   *
   * @param path the path relative to the {@code .editorconfig} file's directory, with a leading
   *     {@code /} and {@code /} between names
   * @return true when it matches
   */
  public boolean matches(String path) {
    Matcher m = pattern.matcher(path);
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
              out.append("\\{").append(translate(inner, ranges, balanced)).append("\\}");
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
