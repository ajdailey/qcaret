package io.github.ajdailey.qcaret.core.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The workspace browser's file filter: {@code ;}-separated globs matched against a file name, such
 * as {@code *.java;*.md}. {@code *} matches any run of characters, {@code ?} one, {@code [abc]} and
 * {@code [!abc]} a set, and {@code {a,b}} either branch. A blank filter matches every name.
 * Immutable.
 */
public final class NameFilter {
  /** True on Windows and macOS, whose default file systems ignore case. */
  public static final boolean PLATFORM_IGNORES_CASE = ignoresCase(System.getProperty("os.name"));

  /** Matches every name. */
  public static final NameFilter ALL = new NameFilter("", false, List.of());

  private final String text;
  private final boolean ignoreCase;
  private final List<Pattern> globs;

  private NameFilter(String text, boolean ignoreCase, List<Pattern> globs) {
    this.text = text;
    this.ignoreCase = ignoreCase;
    this.globs = globs;
  }

  /**
   * Reports whether a platform's file names ignore case.
   *
   * @param osName the {@code os.name} property, or null
   * @return true for Windows and macOS
   */
  static boolean ignoresCase(String osName) {
    String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
    return name.startsWith("windows") || name.startsWith("mac");
  }

  /**
   * Parses a filter.
   *
   * @param text {@code ;}-separated globs; blank for every name
   * @param ignoreCase true to match regardless of case
   * @return the filter
   * @throws IllegalArgumentException for an unclosed {@code [} or <code>{</code>
   */
  public static NameFilter parse(String text, boolean ignoreCase) {
    String kept = text.strip();
    List<Pattern> globs = new ArrayList<Pattern>();
    int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
    for (String part : kept.split(";", -1)) {
      String glob = part.strip();
      if (!glob.isEmpty()) {
        globs.add(Pattern.compile(regex(glob), flags));
      }
    }
    return globs.isEmpty() ? ALL : new NameFilter(kept, ignoreCase, List.copyOf(globs));
  }

  private static String regex(String glob) {
    StringBuilder out = new StringBuilder();
    int braces = 0;
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      switch (c) {
        case '*' -> out.append(".*");
        case '?' -> out.append('.');
        case '[' -> {
          int end = glob.indexOf(']', i + 2);
          if (end < 0) {
            throw new IllegalArgumentException("unclosed [ in " + glob);
          }
          String set = glob.substring(i + 1, end);
          out.append('[');
          if (set.startsWith("!")) {
            out.append('^');
            set = set.substring(1);
          }
          for (int k = 0; k < set.length(); k++) {
            char s = set.charAt(k);
            if ((s == '-' && k > 0 && k < set.length() - 1) || Character.isLetterOrDigit(s)) {
              out.append(s);
            } else {
              out.append('\\').append(s);
            }
          }
          out.append(']');
          i = end;
        }
        case '{' -> {
          braces++;
          out.append("(?:");
        }
        case '}' -> {
          if (braces == 0) {
            out.append("\\}");
          } else {
            braces--;
            out.append(')');
          }
        }
        case ',' -> out.append(braces > 0 ? "|" : ",");
        default -> out.append(Pattern.quote(String.valueOf(c)));
      }
      i++;
    }
    if (braces > 0) {
      throw new IllegalArgumentException("unclosed { in " + glob);
    }
    return out.toString();
  }

  /**
   * Returns the filter as given, without outer blanks.
   *
   * @return the text; empty for {@link #ALL}
   */
  public String text() {
    return text;
  }

  /**
   * Reports whether a file name passes.
   *
   * @param name one path element
   * @return true when a glob matches, or none is given
   */
  public boolean matches(String name) {
    if (globs.isEmpty()) {
      return true;
    }
    for (Pattern glob : globs) {
      if (glob.matcher(name).matches()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof NameFilter filter
        && text.equals(filter.text)
        && ignoreCase == filter.ignoreCase;
  }

  @Override
  public int hashCode() {
    return text.hashCode();
  }

  @Override
  public String toString() {
    return text;
  }
}
