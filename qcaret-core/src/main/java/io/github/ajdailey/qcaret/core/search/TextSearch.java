package io.github.ajdailey.qcaret.core.search;

import io.github.ajdailey.qcaret.core.buffer.ChunkVisitor;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finds every match of a {@link TextMatcher} in one text and describes each by line, column and a
 * snippet of its line, for a HyperSearch result.
 *
 * <ul>
 *   <li>A line longer than {@code maxLineChars} is not searched and is counted in {@link
 *       Outcome#skippedLines}; the text before and after it is. Each searched stretch ends before
 *       the skipped line, so no match runs into it.
 *   <li>A {@link PatternTooComplexException} ends the search: the outcome keeps the matches before
 *       the line that overflowed and names that line in {@link Outcome#tooComplexLine}.
 *   <li>The snippet is the match's first line with control chars as spaces and the leading and
 *       trailing whitespace removed; a line over {@value #SNIPPET_CHARS} chars is cut to that many
 *       around the match. A multi-line match is shown by its first line.
 * </ul>
 */
public final class TextSearch {
  /** Longest snippet. */
  public static final int SNIPPET_CHARS = 500;

  /** Chars kept before the match when a long line is cut. */
  static final int SNIPPET_BEFORE = 100;

  private TextSearch() {}

  /**
   * One match.
   *
   * @param start its first offset
   * @param end the offset after it
   * @param line its first line, from 0
   * @param column its offset in that line, in chars from 0
   * @param snippet the line, or a part of it
   * @param snippetStart where the match starts in the snippet
   * @param snippetEnd where the match ends in the snippet, at most the snippet's length
   * @param hash {@link String#hashCode} of the matched text
   */
  public record Hit(
      int start,
      int end,
      int line,
      int column,
      String snippet,
      int snippetStart,
      int snippetEnd,
      int hash) {}

  /**
   * What one text gave.
   *
   * @param hits the matches by offset
   * @param skippedLines lines not searched for their length
   * @param tooComplexLine the line where the regex overflowed the stack, or -1
   * @param limited true when matches beyond {@code limit} were left out
   */
  public record Outcome(List<Hit> hits, int skippedLines, int tooComplexLine, boolean limited) {}

  /**
   * Searches a text.
   *
   * @param matcher the query
   * @param text the text
   * @param ranges offset pairs to search in, ascending, a match lying wholly inside one; null for
   *     the whole text
   * @param limit the most matches returned
   * @param maxLineChars the longest line searched
   * @param token checked while reading
   * @return the matches and what was skipped
   * @throws java.util.concurrent.CancellationException when canceled
   */
  @WorkerThread
  public static Outcome search(
      TextMatcher matcher,
      CharSequence text,
      int[] ranges,
      int limit,
      int maxLineChars,
      CancellationToken token) {
    Lines lines = text instanceof TextContent content ? new ContentLines(content) : scan(text);
    int[] bounds = ranges == null ? new int[] {0, text.length()} : ranges;
    List<Hit> hits = new ArrayList<Hit>();
    int skipped = 0;
    boolean limited = false;
    for (int r = 0; r + 1 < bounds.length && !limited; r += 2) {
      int from = Math.max(0, bounds[r]);
      int to = Math.min(text.length(), bounds[r + 1]);
      if (to <= from) {
        continue;
      }
      // Each searched stretch ends before a line too long to search.
      int[] skip = longLines(text, lines.start(lines.lineAt(from)), to, maxLineChars, token);
      int at = from;
      for (int k = 0; k <= skip.length && !limited; k += 2) {
        int end = k < skip.length ? Math.min(to, skip[k]) : to;
        if (end > at) {
          int tooComplex = region(matcher, text, lines, at, end, to, ranges, limit, hits, token);
          if (tooComplex >= 0) {
            return new Outcome(hits, skipped, tooComplex, false);
          }
          limited = hits.size() > limit;
        }
        if (k < skip.length) {
          skipped++;
          at = Math.max(at, skip[k + 1]);
        }
      }
    }
    if (hits.size() > limit) {
      hits.subList(limit, hits.size()).clear();
      limited = true;
    }
    return new Outcome(hits, skipped, -1, limited);
  }

  /**
   * Adds the matches starting in {@code [from, to)}, at most one past {@code limit}. On a stack
   * overflow it searches the region line by line to name the line.
   *
   * @return the line that overflowed, or -1
   */
  @WorkerThread
  private static int region(
      TextMatcher matcher,
      CharSequence text,
      Lines lines,
      int from,
      int to,
      int rangeEnd,
      int[] ranges,
      int limit,
      List<Hit> hits,
      CancellationToken token) {
    try {
      add(
          matcher.all(new Prefix(text, to), from, to, room(limit, hits), token),
          text,
          lines,
          rangeEnd,
          ranges,
          hits);
      return -1;
    } catch (PatternTooComplexException e) {
      int line = lines.lineAt(from);
      int at = from;
      while (at < to) {
        int end = Math.min(to, lines.next(line, text.length()));
        try {
          add(
              matcher.all(new Prefix(text, end), at, end, room(limit, hits), token),
              text,
              lines,
              rangeEnd,
              ranges,
              hits);
        } catch (PatternTooComplexException overflow) {
          return line;
        }
        if (hits.size() > limit) {
          return -1;
        }
        at = end;
        line++;
      }
      return lines.lineAt(from);
    }
  }

  /**
   * Finds the lines longer than {@code max} chars that start in {@code [from, to)}, reading the
   * text once in chunks.
   *
   * @return pairs of a line's start and the offset after its break, ascending
   */
  private static int[] longLines(
      CharSequence text, int from, int to, int max, CancellationToken token) {
    LongLines scan = new LongLines(from, max);
    int length = text.length();
    int at = from;
    while (at < length && !scan.past(to)) {
      token.check();
      int end = (int) Math.min(length, (long) at + SCAN_CHARS);
      if (text instanceof TextContent content) {
        content.readChunks(at, end, scan);
      } else {
        scan.chunk(text, at, end);
      }
      at = end;
    }
    return scan.finish(at);
  }

  /** Chars read between cancellation checks while finding long lines. */
  static final int SCAN_CHARS = 1 << 16;

  /** Measures lines as their chars arrive; CR, LF and CR LF each end a line. */
  private static final class LongLines implements ChunkVisitor {
    private final int max;
    private int offset;
    private int lineStart;
    private boolean cr;
    private int[] found = new int[8];
    private int count;

    LongLines(int from, int max) {
      this.offset = from;
      this.lineStart = from;
      this.max = max;
    }

    /** Reports whether every line starting before {@code to} is measured. */
    boolean past(int to) {
      return offset >= to && (lineStart >= to || offset - lineStart > max);
    }

    @Override
    public void chunk(CharSequence chunk, int start, int end) {
      for (int i = start; i < end; i++) {
        char c = chunk.charAt(i);
        if (cr) {
          cr = false;
          if (c == '\n') {
            line(offset - 1, offset + 1);
            offset++;
            continue;
          }
          line(offset - 1, offset);
        }
        if (c == '\r') {
          cr = true;
        } else if (c == '\n') {
          line(offset, offset + 1);
        }
        offset++;
      }
    }

    private void line(int contentEnd, int next) {
      if (contentEnd - lineStart > max) {
        add(lineStart, next);
      }
      lineStart = next;
    }

    private void add(int start, int next) {
      if (count + 2 > found.length) {
        found = Arrays.copyOf(found, found.length * 2);
      }
      found[count++] = start;
      found[count++] = next;
    }

    /** Ends the scan at an offset: the open line counts when it is already too long. */
    int[] finish(int end) {
      if (cr) {
        line(end - 1, end);
      } else if (end - lineStart > max) {
        add(lineStart, end);
      }
      return Arrays.copyOf(found, count);
    }
  }

  /** Matches still wanted: one past the limit, so a cut is detected. */
  private static int room(int limit, List<Hit> hits) {
    return (int) Math.max(0, Math.min(Integer.MAX_VALUE, (long) limit + 1 - hits.size()));
  }

  /**
   * The text up to an offset: a find on it cannot read past a searched stretch into a skipped line
   * or, after an overflow, past one line.
   */
  private static final class Prefix implements CharSequence {
    private final CharSequence text;
    private final int end;

    Prefix(CharSequence text, int end) {
      this.text = text;
      this.end = end;
    }

    @Override
    public int length() {
      return end;
    }

    @Override
    public char charAt(int index) {
      if (index >= end) {
        throw new IndexOutOfBoundsException(index);
      }
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int stop) {
      if (stop > end) {
        throw new IndexOutOfBoundsException(stop);
      }
      return text.subSequence(start, stop);
    }

    @Override
    public String toString() {
      return text.subSequence(0, end).toString();
    }
  }

  private static void add(
      List<Match> found,
      CharSequence text,
      Lines lines,
      int rangeEnd,
      int[] ranges,
      List<Hit> hits) {
    for (Match m : found) {
      if (ranges != null && m.end() > rangeEnd) {
        continue;
      }
      hits.add(hit(text, lines, m));
    }
  }

  private static Hit hit(CharSequence text, Lines lines, Match m) {
    int line = lines.lineAt(m.start());
    int lineStart = lines.start(line);
    int lineEnd = lines.end(line);
    int from = lineStart;
    int to = lineEnd;
    if (to - from > SNIPPET_CHARS) {
      from = Math.max(lineStart, m.start() - SNIPPET_BEFORE);
      to = Math.min(lineEnd, from + SNIPPET_CHARS);
    }
    char[] chars = text.subSequence(from, to).toString().toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (chars[i] < ' ' || chars[i] == 0x7f) {
        chars[i] = ' ';
      }
    }
    int lead = 0;
    while (lead < chars.length && lead < m.start() - from && Character.isWhitespace(chars[lead])) {
      lead++;
    }
    int trail = chars.length;
    while (trail > lead && trail > m.start() - from && Character.isWhitespace(chars[trail - 1])) {
      trail--;
    }
    String snippet = new String(chars, lead, trail - lead);
    int snippetStart = m.start() - from - lead;
    int snippetEnd = Math.min(snippet.length(), m.end() - from - lead);
    int hash = text.subSequence(m.start(), m.end()).toString().hashCode();
    return new Hit(
        m.start(),
        m.end(),
        line,
        m.start() - lineStart,
        snippet,
        snippetStart,
        Math.max(snippetStart, snippetEnd),
        hash);
  }

  /** Line geometry of a text. */
  private interface Lines {
    int lineAt(int offset);

    int start(int line);

    /** The offset of the line's break, or the text's end. */
    int end(int line);

    /** The offset after the line's break. */
    int next(int line, int length);
  }

  private static final class ContentLines implements Lines {
    private final TextContent text;

    ContentLines(TextContent text) {
      this.text = text;
    }

    @Override
    public int lineAt(int offset) {
      return text.lineAt(offset);
    }

    @Override
    public int start(int line) {
      return text.offsetOfLine(line);
    }

    @Override
    public int end(int line) {
      return text.offsetOfLine(line) + text.lineLength(line);
    }

    @Override
    public int next(int line, int length) {
      return line + 1 < text.lineCount() ? text.offsetOfLine(line + 1) : length;
    }
  }

  /** Scans a text for its line starts; CR, LF and CR LF each end a line. */
  private static Lines scan(CharSequence text) {
    int[] starts = new int[16];
    int count = 1;
    int length = text.length();
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
          i++;
        }
        if (count == starts.length) {
          starts = Arrays.copyOf(starts, count * 2);
        }
        starts[count++] = i + 1;
      }
    }
    return new StartLines(text, Arrays.copyOf(starts, count));
  }

  private static final class StartLines implements Lines {
    private final CharSequence text;
    private final int[] starts;

    StartLines(CharSequence text, int[] starts) {
      this.text = text;
      this.starts = starts;
    }

    @Override
    public int lineAt(int offset) {
      int at = Arrays.binarySearch(starts, offset);
      return at >= 0 ? at : -at - 2;
    }

    @Override
    public int start(int line) {
      return starts[line];
    }

    @Override
    public int end(int line) {
      if (line + 1 >= starts.length) {
        return text.length();
      }
      int end = starts[line + 1] - 1;
      if (text.charAt(end) == '\n' && end > starts[line] && text.charAt(end - 1) == '\r') {
        end--;
      }
      return end;
    }

    @Override
    public int next(int line, int length) {
      return line + 1 < starts.length ? starts[line + 1] : length;
    }
  }
}
