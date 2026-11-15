package io.github.ajdailey.qcaret.core.syntax;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a rule's regex source for the first-char filter. Both methods may only widen the set of
 * positions where a rule is tried: a position they exclude is one where the rule cannot match.
 */
final class FirstChars {
  private static final Pattern FLAGS = Pattern.compile("^\\(\\?[a-zA-Z]+\\)");

  /** In a probe frame: the group is a negative lookahead or an atomic group. */
  private static final int OPAQUE = 1;

  /** In a probe frame: an assertion inside the group was removed. */
  private static final int REMOVED = 2;

  private FirstChars() {}

  /**
   * Reports whether a regex may match right after an ASCII letter, digit or underscore: false when
   * each top-level alternative has a leading negative lookbehind matching each of them.
   */
  static boolean afterWord(String source) {
    int at = 0;
    Matcher flags = FLAGS.matcher(source);
    if (flags.find()) {
      at = flags.end();
    }
    List<Integer> alternatives = alternatives(source, at);
    if (alternatives == null) {
      return true;
    }
    for (int start : alternatives) {
      if (!excludesWord(source, start)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the start of each top-level alternative from {@code from}, or null when a {@code \Q}
   * quote stops the scan.
   */
  private static List<Integer> alternatives(String source, int from) {
    List<Integer> starts = new ArrayList<Integer>();
    starts.add(from);
    int depth = 0;
    boolean inClass = false;
    for (int i = from; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        if (source.startsWith("\\Q", i)) {
          return null;
        }
        i++;
      } else if (inClass) {
        inClass = c != ']';
      } else if (c == '[') {
        inClass = true;
        if (i + 1 < source.length() && source.charAt(i + 1) == ']') {
          i++;
        }
      } else if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == '|' && depth == 0) {
        starts.add(i + 1);
      }
    }
    return starts;
  }

  /**
   * Reports whether one of the lookarounds leading the alternative at {@code at} is a negative
   * lookbehind matching each ASCII letter, digit and underscore.
   */
  private static boolean excludesWord(String source, int at) {
    while (source.startsWith("(?=", at)
        || source.startsWith("(?!", at)
        || source.startsWith("(?<=", at)
        || source.startsWith("(?<!", at)) {
      int close = closing(source, at);
      if (close < 0 || quantified(source, close + 1)) {
        return false;
      }
      if (source.startsWith("(?<!", at) && excludesWordChars(source.substring(at + 4, close))) {
        return true;
      }
      at = close + 1;
    }
    return false;
  }

  private static boolean excludesWordChars(String body) {
    Pattern p;
    try {
      p = Pattern.compile(body);
    } catch (IllegalArgumentException e) {
      return false;
    }
    for (char c = 0; c < Syntax.NON_ASCII; c++) {
      boolean word = Character.isLetterOrDigit(c) || c == '_';
      if (word && !p.matcher(String.valueOf(c)).matches()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns a regex without its lookbehinds, word boundaries and the negative lookaheads holding
   * either, compiled for probing first chars; null when it cannot be read that way. Matched against
   * one char, a lookbehind or boundary sees the input's start instead of the text before the
   * position. Removing one only widens the match, except under a negative lookahead, an atomic
   * group, a quantifier or a possessive group, where the regex is not probed.
   */
  static Pattern probe(String source) {
    StringBuilder out = new StringBuilder(source.length());
    int[] frames = new int[source.length() + 1];
    int depth = 0;
    boolean inClass = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (source.startsWith("\\Q", i)) {
        int quoteEnd = source.indexOf("\\E", i + 2);
        int stop = quoteEnd < 0 ? source.length() : quoteEnd + 2;
        out.append(source, i, stop);
        i = stop - 1;
      } else if (c == '\\') {
        char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
        if (!inClass && (next == 'b' || next == 'B')) {
          if (!removable(frames, depth, source, i + 2)) {
            return null;
          }
        } else {
          out.append(source, i, Math.min(i + 2, source.length()));
        }
        i++;
      } else if (inClass) {
        inClass = c != ']';
        out.append(c);
      } else if (c == '[') {
        inClass = true;
        out.append(c);
        if (i + 1 < source.length() && source.charAt(i + 1) == ']') {
          out.append(']');
          i++;
        }
      } else if (source.startsWith("(?<=", i)
          || source.startsWith("(?<!", i)
          || (source.startsWith("(?!", i) && holdsAssertion(source, i))) {
        int close = closing(source, i);
        if (close < 0 || !removable(frames, depth, source, close + 1)) {
          return null;
        }
        i = close;
      } else if (c == '(') {
        boolean opaque = source.startsWith("(?!", i) || source.startsWith("(?>", i);
        frames[depth++] = opaque ? OPAQUE : 0;
        out.append(c);
      } else if (c == ')' && depth > 0) {
        int frame = frames[--depth];
        if ((frame & REMOVED) != 0) {
          if (possessive(source, i + 1)) {
            return null;
          }
          if (depth > 0) {
            frames[depth - 1] |= REMOVED;
          }
        }
        out.append(c);
      } else {
        out.append(c);
      }
    }
    try {
      return Pattern.compile(out.toString());
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Reports whether the group opened at {@code open} holds a lookbehind or word boundary; true when
   * the group is not closed.
   */
  private static boolean holdsAssertion(String source, int open) {
    int close = closing(source, open);
    if (close < 0) {
      return true;
    }
    boolean inClass = false;
    for (int i = open + 1; i < close; i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        char next = source.charAt(i + 1);
        if (!inClass && (next == 'b' || next == 'B')) {
          return true;
        }
        i++;
      } else if (inClass) {
        inClass = c != ']';
      } else if (c == '[') {
        inClass = true;
        if (source.charAt(i + 1) == ']') {
          i++;
        }
      } else if (source.startsWith("(?<=", i) || source.startsWith("(?<!", i)) {
        return true;
      }
    }
    return false;
  }

  /** Marks an assertion removed in the innermost group; false when removing it may narrow. */
  private static boolean removable(int[] frames, int depth, String source, int after) {
    if (quantified(source, after)) {
      return false;
    }
    for (int i = 0; i < depth; i++) {
      if ((frames[i] & OPAQUE) != 0) {
        return false;
      }
    }
    if (depth > 0) {
      frames[depth - 1] |= REMOVED;
    }
    return true;
  }

  private static boolean quantified(String source, int at) {
    return at < source.length() && "?*+{".indexOf(source.charAt(at)) >= 0;
  }

  /** Reports whether a possessive quantifier starts at {@code at}. */
  private static boolean possessive(String source, int at) {
    if (!quantified(source, at)) {
      return false;
    }
    int last = at;
    if (source.charAt(at) == '{') {
      last = source.indexOf('}', at);
      if (last < 0) {
        return false;
      }
    }
    return last + 1 < source.length() && source.charAt(last + 1) == '+';
  }

  /** Returns the index of the parenthesis closing the group opened at {@code open}, or -1. */
  private static int closing(String source, int open) {
    int depth = 0;
    boolean inClass = false;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') {
        if (source.startsWith("\\Q", i)) {
          return -1;
        }
        i++;
      } else if (inClass) {
        inClass = c != ']';
      } else if (c == '[') {
        inClass = true;
        if (i + 1 < source.length() && source.charAt(i + 1) == ']') {
          i++;
        }
      } else if (c == '(') {
        depth++;
      } else if (c == ')' && --depth == 0) {
        return i;
      }
    }
    return -1;
  }
}
