package io.github.ajdailey.qcaret.core.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One parsed {@code .editorconfig} file: the {@code root} flag of its preamble and its sections in
 * file order. A line is blank, a comment ({@code #} or {@code ;} first), a section ({@code [glob]},
 * the glob running to the last {@code ]}) or {@code key = value}; any other line, a section over
 * {@value #MAX_SECTION} chars, a key over {@value #MAX_KEY} or a value over {@value #MAX_VALUE}
 * makes the file malformed. Keys are lower-cased, and so are the values of the standard properties;
 * a {@code #} or {@code ;} after a value's first char belongs to the value. A UTF-8 byte order mark
 * is skipped.
 *
 * @param root true when the preamble sets {@code root = true}
 * @param sections the sections in file order
 */
public record EditorConfigFile(boolean root, List<Section> sections) {
  /** Longest section name, in chars. */
  public static final int MAX_SECTION = 4096;

  /** Longest key, in chars. */
  public static final int MAX_KEY = 1024;

  /** Longest value, in chars. */
  public static final int MAX_VALUE = 4096;

  /** The properties whose values are lower-cased. */
  private static final Set<String> STANDARD =
      Set.of(
          "indent_style",
          "indent_size",
          "tab_width",
          "end_of_line",
          "charset",
          "trim_trailing_whitespace",
          "insert_final_newline",
          "root");

  /**
   * One section.
   *
   * @param glob the compiled section name
   * @param properties its assignments in file order, a later one for a key replacing the earlier
   */
  public record Section(EditorConfigGlob glob, Map<String, String> properties) {}

  /** Thrown for a malformed file. */
  public static final class MalformedException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message names the line
     */
    public MalformedException(String message) {
      super(message);
    }
  }

  /**
   * Parses a file's text.
   *
   * @param text the file decoded as UTF-8
   * @return the file
   * @throws MalformedException for a line the format does not allow
   */
  public static EditorConfigFile parse(String text) throws MalformedException {
    boolean root = false;
    List<Section> sections = new ArrayList<Section>();
    Map<String, String> current = null;
    int start = !text.isEmpty() && text.charAt(0) == '﻿' ? 1 : 0;
    int number = 0;
    while (start <= text.length()) {
      int end = lineEnd(text, start);
      String line = text.substring(start, end).strip();
      number++;
      start = next(text, end);
      if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == ';') {
        continue;
      }
      if (line.charAt(0) == '[') {
        int close = line.lastIndexOf(']');
        if (close < 1 || close != line.length() - 1 || close - 1 > MAX_SECTION) {
          throw new MalformedException("Line " + number + " is not a section");
        }
        current = new LinkedHashMap<String, String>();
        sections.add(new Section(EditorConfigGlob.compile(line.substring(1, close)), current));
        continue;
      }
      int equals = line.indexOf('=');
      if (equals <= 0) {
        throw new MalformedException("Line " + number + " is not a key and value");
      }
      String key = lower(line.substring(0, equals).strip());
      String value = line.substring(equals + 1).strip();
      if (key.isEmpty() || key.length() > MAX_KEY || value.length() > MAX_VALUE) {
        throw new MalformedException("Line " + number + " is too long");
      }
      if (STANDARD.contains(key)) {
        value = lower(value);
      }
      if (current == null) {
        if (key.equals("root")) {
          root = value.equals("true");
        }
      } else {
        current.remove(key);
        current.put(key, value);
      }
    }
    return new EditorConfigFile(root, sections);
  }

  /**
   * Lower-cases the ASCII letters of a text, as the format's keys and standard values are ASCII.
   *
   * @param text the text
   * @return the text with A to Z lower-cased
   */
  static String lower(String text) {
    char[] chars = text.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] >= 'A' && chars[i] <= 'Z') {
        chars[i] = (char) (chars[i] + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  private static int lineEnd(String text, int from) {
    for (int i = from; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        return i;
      }
    }
    return text.length();
  }

  /**
   * Returns the start of the line after one ending at {@code end}, past the text's end at the end.
   */
  private static int next(String text, int end) {
    if (end >= text.length()) {
      return text.length() + 1;
    }
    if (text.charAt(end) == '\r' && end + 1 < text.length() && text.charAt(end + 1) == '\n') {
      return end + 2;
    }
    return end + 1;
  }
}
