package io.github.ajdailey.qcaret.core.text;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the settings a file declares in comments: vim modelines and Emacs file variables in the
 * first and last {@value #LINES} lines, the Emacs {@code Local Variables:} block in the last
 * {@value #TAIL_CHARS} chars, and the coding line {@link EncodingDetector} uses.
 *
 * <table>
 *   <caption>Recognized settings</caption>
 *   <tr><th>Setting</th><th>vim</th><th>Emacs</th></tr>
 *   <tr><td>Tab width</td><td>{@code ts}, {@code tabstop}</td><td>{@code tab-width}</td></tr>
 *   <tr><td>Indent width</td><td>{@code sw}, {@code shiftwidth}, {@code sts},
 *       {@code softtabstop}</td><td>none</td></tr>
 *   <tr><td>Spaces</td><td>{@code et}, {@code expandtab}, {@code noet},
 *       {@code noexpandtab}</td><td>{@code indent-tabs-mode} ({@code nil} for spaces)</td></tr>
 *   <tr><td>Line ending</td><td>{@code ff}, {@code fileformat}: {@code unix}, {@code dos},
 *       {@code mac}</td><td>a {@code -unix}, {@code -dos} or {@code -mac} coding suffix</td></tr>
 *   <tr><td>Charset</td><td>{@code fenc}, {@code fileencoding}</td><td>{@code coding}</td></tr>
 * </table>
 *
 * <p>A vim modeline is {@code vi:}, {@code vim:}, {@code Vim:} or {@code ex:} at a line's start or
 * after a blank ({@code ex:} after a blank only), then either {@code set} options up to the next
 * unescaped {@code :}, or options separated by blanks or {@code :} to the line's end. An Emacs
 * first line is {@code -*- ... -*-} on line 1, or on line 2 after a {@code #!} line. With spaces,
 * the width is {@code sts}, else {@code sw}, else {@code ts}; with tabs it is {@code ts}, else
 * {@code sw}. vim's settings replace Emacs's. Unknown options and values out of range are ignored.
 */
public final class Modelines {
  /** Lines read at each end of a file. */
  public static final int LINES = 5;

  /** Chars at a file's end searched for an Emacs {@code Local Variables:} block. */
  public static final int TAIL_CHARS = 3000;

  /** Bytes searched for a coding line. */
  private static final int CODING_CHARS = 1024;

  private static final Pattern CODING = Pattern.compile("coding[:=]\\s*([-\\w.]{1,40})");
  private static final Pattern SET = Pattern.compile("\\s*se(?:t)?\\s+");

  private Modelines() {}

  /**
   * Returns the charset a coding line names: {@code coding:} or {@code coding=} in the first two
   * lines and first {@value #CODING_CHARS} chars, as Python, Emacs and vim's {@code fileencoding}
   * write it.
   *
   * @param head the file's first chars, bytes read as ISO-8859-1
   * @return the charset name as written, or null
   */
  public static String codingLine(String head) {
    String lines = firstLines(head.substring(0, Math.min(head.length(), CODING_CHARS)), 2);
    Matcher coding = CODING.matcher(lines);
    return coding.find() ? coding.group(1) : null;
  }

  /**
   * Parses the modelines of a text given by its two ends.
   *
   * @param head the text's start; its first {@value #LINES} lines are read
   * @param tail the text's last {@value #TAIL_CHARS} chars or fewer; its last {@value #LINES} lines
   *     are read, and the whole for a {@code Local Variables:} block
   * @return the settings, {@link Modeline#NONE} when no modeline is found
   */
  public static Modeline parse(String head, String tail) {
    List<String> first = lines(firstLines(head, LINES));
    List<String> last = lastLines(lines(tail), LINES);
    Settings s = new Settings();
    if (!first.isEmpty()) {
      int line = first.get(0).startsWith("#!") && first.size() > 1 ? 1 : 0;
      emacsFirstLine(first.get(line), s);
    }
    localVariables(lines(tail), s);
    for (String line : first) {
      vim(line, s);
    }
    for (String line : last) {
      vim(line, s);
    }
    return s.result();
  }

  /**
   * Returns the charset a name stands for: as Java names it, or without hyphens ({@code latin-1}).
   *
   * @param name the name
   * @return the charset, or null when this Java lacks it
   */
  public static Charset charset(String name) {
    String n = name.strip();
    if (!supported(n)) {
      n = n.replace("-", "");
      if (!supported(n)) {
        return null;
      }
    }
    return Charset.forName(n);
  }

  private static boolean supported(String name) {
    try {
      return !name.isEmpty() && Charset.isSupported(name);
    } catch (IllegalCharsetNameException e) {
      return false;
    }
  }

  /** The values found so far; vim's found last replace Emacs's. */
  private static final class Settings {
    int ts;
    int sw;
    int sts;
    Boolean spaces;
    LineEnding ending;
    Charset charset;
    boolean vim;
    boolean emacs;

    Modeline result() {
      if (!vim && !emacs) {
        return Modeline.NONE;
      }
      int width;
      if (Boolean.TRUE.equals(spaces)) {
        width = sts > 0 ? sts : sw > 0 ? sw : ts;
      } else {
        width = ts > 0 ? ts : sw;
      }
      String kind = vim && emacs ? "vim, emacs" : vim ? "vim" : "emacs";
      return new Modeline(width, spaces, ending, charset, kind);
    }
  }

  private static void emacsFirstLine(String line, Settings s) {
    int open = line.indexOf("-*-");
    int close = open < 0 ? -1 : line.indexOf("-*-", open + 3);
    if (close < 0) {
      return;
    }
    String body = line.substring(open + 3, close);
    if (body.indexOf(':') < 0) {
      return;
    }
    for (String pair : body.split(";")) {
      s.emacs |= emacsVariable(pair, s);
    }
  }

  /** Reads the last {@code Local Variables:} block of the tail, up to its {@code End:} line. */
  private static void localVariables(List<String> lines, Settings s) {
    int start = -1;
    for (int i = lines.size() - 1; i >= 0 && start < 0; i--) {
      if (lines.get(i).contains("Local Variables:")) {
        start = i;
      }
    }
    if (start < 0) {
      return;
    }
    String header = lines.get(start);
    int at = header.indexOf("Local Variables:");
    String prefix = header.substring(0, at);
    String suffix = header.substring(at + "Local Variables:".length()).strip();
    List<String> found = new ArrayList<String>();
    for (int i = start + 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (!line.startsWith(prefix)) {
        continue;
      }
      line = line.substring(prefix.length());
      if (!suffix.isEmpty() && line.stripTrailing().endsWith(suffix)) {
        line = line.stripTrailing();
        line = line.substring(0, line.length() - suffix.length());
      }
      line = line.strip();
      if (line.equals("End:")) {
        for (String pair : found) {
          s.emacs |= emacsVariable(pair, s);
        }
        return;
      }
      found.add(line);
    }
  }

  /** Applies one {@code name: value} pair; false for one QCaret does not apply. */
  private static boolean emacsVariable(String pair, Settings s) {
    int colon = pair.indexOf(':');
    if (colon < 0) {
      return false;
    }
    String key = EditorConfigFile.lower(pair.substring(0, colon).strip());
    String value = pair.substring(colon + 1).strip();
    switch (key) {
      case "tab-width" -> s.ts = width(value, s.ts);
      case "indent-tabs-mode" -> s.spaces = value.equals("nil");
      case "coding" -> coding(value, s);
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Reads an Emacs coding system: a charset with an optional line-ending suffix. */
  private static void coding(String value, Settings s) {
    String name = EditorConfigFile.lower(value);
    LineEnding ending = null;
    if (name.endsWith("-unix")) {
      ending = LineEnding.LF;
    } else if (name.endsWith("-dos")) {
      ending = LineEnding.CRLF;
    } else if (name.endsWith("-mac")) {
      ending = LineEnding.CR;
    }
    if (ending != null) {
      s.ending = ending;
      name = name.substring(0, name.lastIndexOf('-'));
    }
    Charset charset = charset(name);
    if (charset != null) {
      s.charset = charset;
    }
  }

  private static void vim(String line, Settings s) {
    int start = vimStart(line);
    if (start < 0) {
      return;
    }
    String rest = line.substring(start);
    Matcher set = SET.matcher(rest);
    List<String> options = new ArrayList<String>();
    if (set.lookingAt()) {
      StringBuilder option = new StringBuilder();
      boolean closed = false;
      for (int i = set.end(); i < rest.length() && !closed; i++) {
        char c = rest.charAt(i);
        if (c == '\\' && i + 1 < rest.length() && rest.charAt(i + 1) == ':') {
          option.append(':');
          i++;
        } else if (c == ':') {
          closed = true;
        } else if (c == ' ' || c == '\t') {
          add(options, option);
        } else {
          option.append(c);
        }
      }
      if (!closed) {
        return;
      }
      add(options, option);
    } else {
      for (String option : rest.split("[\\s:]+")) {
        if (!option.isEmpty()) {
          options.add(option);
        }
      }
    }
    for (String option : options) {
      s.vim |= vimOption(option, s);
    }
  }

  /**
   * Returns the index after the first {@code vi:}, {@code vim:}, {@code vimNNN:}, {@code Vim:} or
   * {@code ex:} that starts the line or follows a blank ({@code ex:} a blank only), or -1.
   */
  private static int vimStart(String line) {
    for (int i = 0; i < line.length(); i++) {
      boolean blank = i > 0 && Character.isWhitespace(line.charAt(i - 1));
      if (i > 0 && !blank) {
        continue;
      }
      if (blank && line.startsWith("ex:", i)) {
        return i + 3;
      }
      if (line.startsWith("Vim:", i)) {
        return i + 4;
      }
      if (line.startsWith("vi:", i)) {
        return i + 3;
      }
      if (line.startsWith("vim", i)) {
        int at = i + 3;
        if (at < line.length() && "<=>".indexOf(line.charAt(at)) >= 0) {
          at++;
        }
        while (at < line.length() && line.charAt(at) >= '0' && line.charAt(at) <= '9') {
          at++;
        }
        if (at < line.length() && line.charAt(at) == ':') {
          return at + 1;
        }
      }
    }
    return -1;
  }

  private static void add(List<String> options, StringBuilder option) {
    if (option.length() > 0) {
      options.add(option.toString());
      option.setLength(0);
    }
  }

  /** Applies one option; false for one QCaret does not apply. */
  private static boolean vimOption(String option, Settings s) {
    int equals = option.indexOf('=');
    String name = equals < 0 ? option : option.substring(0, equals);
    String value = equals < 0 ? "" : option.substring(equals + 1);
    switch (name) {
      case "ts", "tabstop" -> s.ts = width(value, s.ts);
      case "sw", "shiftwidth" -> s.sw = width(value, s.sw);
      case "sts", "softtabstop" -> s.sts = width(value, s.sts);
      case "et", "expandtab" -> s.spaces = Boolean.TRUE;
      case "noet", "noexpandtab" -> s.spaces = Boolean.FALSE;
      case "ff", "fileformat" -> {
        switch (value) {
          case "unix" -> s.ending = LineEnding.LF;
          case "dos" -> s.ending = LineEnding.CRLF;
          case "mac" -> s.ending = LineEnding.CR;
          default -> {
            return false;
          }
        }
      }
      case "fenc", "fileencoding" -> {
        Charset charset = value.isEmpty() ? null : charset(value);
        if (charset != null) {
          s.charset = charset;
        }
      }
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Parses a width from 1 to 16; another value keeps {@code current}. */
  private static int width(String value, int current) {
    try {
      int n = Integer.parseInt(value.strip());
      return n >= 1 && n <= 16 ? n : current;
    } catch (NumberFormatException e) {
      return current;
    }
  }

  /** Returns the text up to the end of its {@code count}th line, or all of it. */
  static String firstLines(String text, int count) {
    int end = 0;
    for (int i = 0; i < count && end >= 0; i++) {
      int lf = text.indexOf('\n', end);
      int cr = text.indexOf('\r', end);
      int next = lf < 0 ? cr : cr < 0 ? lf : Math.min(lf, cr);
      if (next >= 0
          && text.charAt(next) == '\r'
          && next + 1 < text.length()
          && text.charAt(next + 1) == '\n') {
        next++;
      }
      end = next < 0 ? -1 : next + 1;
    }
    return end < 0 ? text : text.substring(0, end);
  }

  private static List<String> lines(String text) {
    List<String> out = new ArrayList<String>();
    int start = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        out.add(text.substring(start, i));
        if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        start = i + 1;
      }
    }
    if (start < text.length()) {
      out.add(text.substring(start));
    }
    return out;
  }

  private static List<String> lastLines(List<String> lines, int count) {
    return lines.subList(Math.max(0, lines.size() - count), lines.size());
  }
}
