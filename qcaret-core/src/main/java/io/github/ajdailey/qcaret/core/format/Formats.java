package io.github.ajdailey.qcaret.core.format;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Pretty Print, Minify and Check for XML ({@link XmlCheck}, {@link XmlLayout}) and JSON ({@link
 * JsonLayout}). Each non-empty selection is a range of its own; with none, the whole text is one
 * range. The whole text must be one document; a selection may also be an XML fragment, several
 * top-level items parsed inside a synthetic root, which must not hold an XML declaration or a
 * DOCTYPE. Formatting changes only whitespace between tokens; every token's text stays as it is.
 *
 * <p>An error in any range fails the run and changes nothing. Pretty Print and Minify refuse what
 * Check reports. Each inserted line of a selection starts with the leading spaces and tabs of the
 * selection's first line. A selection endpoint keeps its place in a token and moves after a gap
 * that changed.
 */
public final class Formats {
  /** A text format. */
  public enum Language {
    /** XML 1.0. */
    XML,
    /** JSON, with comments unless the layout is strict. */
    JSON
  }

  /** An operation. */
  public enum Mode {
    /** One item per line, indented. */
    PRETTY_PRINT,
    /** Every removable gap removed. */
    MINIFY,
    /** Well-formedness only; no edit. */
    CHECK
  }

  private Formats() {}

  /**
   * Runs an operation on a worker.
   *
   * @param language the format
   * @param mode the operation
   * @param text the text
   * @param set the selections
   * @param layout the indent, line ending, JSON strictness and output limit
   * @param token checked throughout
   * @return the edit, the error, or neither
   * @throws CancellationException when canceled
   */
  public static FormatResult run(
      Language language,
      Mode mode,
      TextContent text,
      SelectionSet set,
      FormatLayout layout,
      CancellationToken token) {
    List<int[]> ranges = ranges(text, set);
    List<Replacement> replacements = new ArrayList<Replacement>();
    int[][] maps = new int[ranges.size()][];
    int[] deltas = new int[ranges.size()];
    boolean comments = false;
    boolean fragment = false;
    boolean selected = selected(set);
    for (int r = 0; r < ranges.size(); r++) {
      int start = ranges.get(r)[0];
      int end = ranges.get(r)[1];
      String source = text.subSequence(start, end).toString();
      int[] points = points(set, start, end);
      String base =
          ranges.size() == 1 && start == 0 && end == text.length() ? "" : base(text, start);
      Output out = new Output(source, mode == Mode.CHECK ? null : layout, base, points, token);
      try {
        if (language == Language.XML) {
          boolean asFragment = selected && !XmlLayout.singleRoot(source);
          fragment |= asFragment;
          if (asFragment) {
            XmlCheck.checkFragment(source, token);
          } else {
            XmlCheck.check(source, token);
          }
          if (out.enabled()) {
            XmlLayout.layout(source, out, mode == Mode.PRETTY_PRINT);
          }
        } else {
          comments |=
              JsonLayout.layout(source, out, mode == Mode.PRETTY_PRINT, layout.strictJson());
        }
      } catch (FormatException e) {
        return new FormatResult(
            null, null, error(text, start + e.at(), e.getMessage()), comments, fragment);
      }
      token.check();
      String formatted = out.text();
      if (formatted == null) {
        continue;
      }
      maps[r] = mapPoints(points, out.mapped());
      deltas[r] = formatted.length() - source.length();
      add(replacements, trimmed(start, source, formatted));
    }
    if (replacements.isEmpty()) {
      return new FormatResult(null, null, null, comments, fragment);
    }
    SelectionSet after = after(set, ranges, maps, deltas);
    return new FormatResult(
        EditBatch.of(EditBatch.Kind.COMMAND, replacements), after, null, comments, fragment);
  }

  private static boolean selected(SelectionSet set) {
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** Returns the non-empty selections' ranges, or the whole text when there are none. */
  private static List<int[]> ranges(TextContent text, SelectionSet set) {
    List<int[]> ranges = new ArrayList<int[]>();
    for (Selection s : set.selections()) {
      if (!s.isEmpty()) {
        ranges.add(new int[] {s.start(), s.end()});
      }
    }
    if (ranges.isEmpty()) {
      ranges.add(new int[] {0, text.length()});
    }
    return ranges;
  }

  /** Returns the spaces and tabs that start the line holding {@code offset}. */
  private static String base(TextContent text, int offset) {
    int start = text.offsetOfLine(text.lineAt(offset));
    int end = start;
    int limit = text.length();
    while (end < limit && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
      end++;
    }
    return text.subSequence(start, end).toString();
  }

  /** Returns the selection endpoints in {@code [start, end]}, relative to start, ascending. */
  private static int[] points(SelectionSet set, int start, int end) {
    int[] points = new int[set.size() * 2];
    int count = 0;
    for (Selection s : set.selections()) {
      for (int p : new int[] {s.anchor(), s.caret()}) {
        if (p >= start && p <= end) {
          points[count++] = p - start;
        }
      }
    }
    int[] sorted = Arrays.copyOf(points, count);
    Arrays.sort(sorted);
    return sorted;
  }

  /** Pairs each point with its mapped offset: {@code [p0, m0, p1, m1, ...]}. */
  private static int[] mapPoints(int[] points, int[] mapped) {
    int[] map = new int[points.length * 2];
    for (int i = 0; i < points.length; i++) {
      map[i * 2] = points[i];
      map[i * 2 + 1] = mapped[i];
    }
    return map;
  }

  /** Returns the replacement of one range without the prefix and suffix that did not change. */
  private static Replacement trimmed(int start, String source, String formatted) {
    int prefix = 0;
    int limit = Math.min(source.length(), formatted.length());
    while (prefix < limit && source.charAt(prefix) == formatted.charAt(prefix)) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < limit - prefix
        && source.charAt(source.length() - 1 - suffix)
            == formatted.charAt(formatted.length() - 1 - suffix)) {
      suffix++;
    }
    if (prefix == source.length() && prefix == formatted.length()) {
      return null;
    }
    return new Replacement(
        start + prefix,
        source.length() - prefix - suffix,
        formatted.substring(prefix, formatted.length() - suffix));
  }

  /**
   * Adds a replacement; one that would share an offset with the previous one, an insert where the
   * previous ends, joins it.
   */
  private static void add(List<Replacement> out, Replacement r) {
    if (r == null) {
      return;
    }
    if (!out.isEmpty()) {
      Replacement last = out.get(out.size() - 1);
      if (last.end() == r.offset() && (last.removeLength() == 0 || r.removeLength() == 0)) {
        out.set(
            out.size() - 1,
            new Replacement(
                last.offset(), last.removeLength() + r.removeLength(), last.text() + r.text()));
        return;
      }
    }
    out.add(r);
  }

  /** Maps every selection through the formatted ranges. */
  private static SelectionSet after(
      SelectionSet set, List<int[]> ranges, int[][] maps, int[] deltas) {
    List<Selection> after = new ArrayList<Selection>(set.size());
    for (Selection s : set.selections()) {
      after.add(
          Selection.of(
              map(s.anchor(), ranges, maps, deltas), map(s.caret(), ranges, maps, deltas)));
    }
    return SelectionSet.of(after, set.primaryIndex());
  }

  private static int map(int offset, List<int[]> ranges, int[][] maps, int[] deltas) {
    int shift = 0;
    for (int r = 0; r < ranges.size(); r++) {
      int start = ranges.get(r)[0];
      int end = ranges.get(r)[1];
      if (offset < start) {
        break;
      }
      if (offset <= end) {
        int[] map = maps[r];
        int key = offset - start;
        int low = 0;
        int high = map.length / 2 - 1;
        while (low <= high) {
          int mid = (low + high) >>> 1;
          int p = map[mid * 2];
          if (p < key) {
            low = mid + 1;
          } else if (p > key) {
            high = mid - 1;
          } else {
            return start + shift + map[mid * 2 + 1];
          }
        }
        return offset + shift;
      }
      shift += deltas[r];
    }
    return offset + shift;
  }

  /** Returns the error at an offset of the whole text. */
  private static FormatError error(TextContent text, int offset, String reason) {
    int line = text.lineAt(offset);
    return new FormatError(offset, line + 1, offset - text.offsetOfLine(line) + 1, reason);
  }
}
