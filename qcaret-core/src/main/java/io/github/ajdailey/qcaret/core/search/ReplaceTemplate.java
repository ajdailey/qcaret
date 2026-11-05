package io.github.ajdailey.qcaret.core.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * A replacement text. For a regex query, {@code $n} inserts group {@code n}, taking further digits
 * while the number stays a group of the pattern; {@code ${name}} inserts a named group; a group
 * that did not take part inserts nothing; {@code \n} inserts LF, {@code \t} a tab, and a backslash
 * before any other char inserts that char. For a literal query the text is inserted as is.
 * Immutable.
 */
public final class ReplaceTemplate {
  private final List<Object> parts;

  private ReplaceTemplate(List<Object> parts) {
    this.parts = parts;
  }

  /**
   * Parses a replacement for a pattern.
   *
   * @param text the replacement text
   * @param pattern the compiled pattern, for its groups
   * @param regex false to take {@code text} literally
   * @return the template
   * @throws IllegalArgumentException for a trailing backslash, a {@code $} without a group, or a
   *     group the pattern lacks
   */
  static ReplaceTemplate parse(String text, Pattern pattern, boolean regex) {
    List<Object> parts = new ArrayList<Object>();
    if (!regex) {
      parts.add(text);
      return new ReplaceTemplate(parts);
    }
    int groups = pattern.matcher("").groupCount();
    Map<String, Integer> named = pattern.namedGroups();
    StringBuilder literal = new StringBuilder();
    int i = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\') {
        if (i + 1 >= text.length()) {
          throw new IllegalArgumentException("Trailing backslash in the replacement");
        }
        char next = text.charAt(i + 1);
        literal.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
        i += 2;
      } else if (c == '$') {
        flush(parts, literal);
        i = group(text, i + 1, groups, named, parts);
      } else {
        literal.append(c);
        i++;
      }
    }
    flush(parts, literal);
    return new ReplaceTemplate(parts);
  }

  private static void flush(List<Object> parts, StringBuilder literal) {
    if (literal.length() > 0) {
      parts.add(literal.toString());
      literal.setLength(0);
    }
  }

  /** Adds the group reference starting at {@code at}; returns the index after it. */
  private static int group(
      String text, int at, int groups, Map<String, Integer> named, List<Object> parts) {
    if (at < text.length() && text.charAt(at) == '{') {
      int close = text.indexOf('}', at);
      if (close < 0) {
        throw new IllegalArgumentException("Unclosed ${ in the replacement");
      }
      String name = text.substring(at + 1, close);
      Integer number = named.get(name);
      if (number == null) {
        throw new IllegalArgumentException("No group named " + name);
      }
      parts.add(number);
      return close + 1;
    }
    if (at >= text.length() || !Character.isDigit(text.charAt(at))) {
      throw new IllegalArgumentException("$ without a group number in the replacement");
    }
    int number = text.charAt(at) - '0';
    if (number > groups) {
      throw new IllegalArgumentException("No group " + number);
    }
    int i = at + 1;
    while (i < text.length() && Character.isDigit(text.charAt(i))) {
      int longer = number * 10 + (text.charAt(i) - '0');
      if (longer > groups) {
        break;
      }
      number = longer;
      i++;
    }
    parts.add(number);
    return i;
  }

  /**
   * Returns the text that replaces a match.
   *
   * @param match the match, with its groups
   * @return the replacement
   */
  String expand(MatchResult match) {
    StringBuilder out = new StringBuilder();
    for (Object part : parts) {
      if (part instanceof Integer group) {
        String value = match.group(group);
        if (value != null) {
          out.append(value);
        }
      } else {
        out.append((String) part);
      }
    }
    return out.toString();
  }
}
