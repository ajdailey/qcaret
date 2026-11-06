package io.github.ajdailey.qcaret.core.document;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.text.LineEndingStats;
import java.io.IOException;

/**
 * Finds the one replacement that turns a text into a file's text, the span between their common
 * prefix and common suffix, from two passes over the file and without holding the file's text.
 */
final class TextDifference {
  private static final int STAGE_CHARS = 1 << 12;

  private TextDifference() {}

  /** The first pass: the common prefix, the file's char count and its line endings. */
  static final class Prefix implements TextSink {
    private final int currentLength;
    private final TextCursor cursor;
    private final long buffers;
    private final LineEndingStats.Counter endings = new LineEndingStats.Counter();
    private long length;
    private int prefix;
    private boolean matching = true;

    Prefix(TextContent current, long buffers) {
      this.currentLength = current.length();
      this.cursor = current.cursor(0);
      this.buffers = buffers;
    }

    @Override
    public void append(char[] chars, int from, int to) throws IOException {
      length += to - from;
      if (length > DocumentFiles.MAX_BYTES) {
        throw new IOException("Text too large: over " + DocumentFiles.MAX_BYTES + " chars");
      }
      endings.add(chars, from, to);
      for (int i = from; matching && i < to; i++) {
        matching = prefix < currentLength && cursor.next() == chars[i];
        if (matching) {
          prefix++;
        }
      }
    }

    @Override
    public long heapBytes() {
      return buffers;
    }

    boolean equalsCurrent() {
      return prefix == length && prefix == currentLength;
    }

    long length() {
      return length;
    }

    LineEndingStats endings() {
      return endings.stats();
    }
  }

  /**
   * The second pass: compares each char after the prefix with the current text's char at the same
   * distance from the end. A run of equal chars is only counted, since the current text holds it,
   * and joins the span when a later char differs; the run left at the end is the common suffix.
   */
  static final class Span implements TextSink {
    private final TextContent current;
    private final int prefix;
    private final long shift;
    private final long compareFrom;
    private final long buffers;
    private final TextCursor cursor;
    private final RopeStorage.Builder span = new RopeStorage.Builder();
    private final char[] stage = new char[STAGE_CHARS];
    private int staged;
    private long at;
    private int run;
    private RopeStorage built;

    Span(TextContent current, Prefix first, long buffers) {
      this.current = current;
      this.prefix = first.prefix;
      this.shift = current.length() - first.length;
      this.compareFrom = Math.max(prefix, prefix - shift);
      this.buffers = buffers;
      this.cursor = current.cursor((int) (compareFrom + shift));
    }

    @Override
    public void append(char[] chars, int from, int to) {
      for (int i = from; i < to; i++) {
        long position = at++;
        if (position < prefix) {
          continue;
        }
        if (position >= compareFrom && cursor.hasNext() && cursor.next() == chars[i]) {
          run++;
          continue;
        }
        takeRun(position);
        put(chars[i]);
      }
      flush();
    }

    /** Moves the counted run, which ends before {@code position}, into the span. */
    private void takeRun(long position) {
      if (run > 0) {
        TextCursor equal = current.cursor((int) (position + shift - run));
        for (int i = 0; i < run; i++) {
          put(equal.next());
        }
        run = 0;
      }
    }

    private void put(char c) {
      if (staged == stage.length) {
        flush();
      }
      stage[staged++] = c;
    }

    private void flush() {
      span.append(stage, 0, staged);
      staged = 0;
    }

    /** Charges the span twice: its leaves here, then a {@code String} and leaves when applied. */
    @Override
    public long heapBytes() {
      return buffers + 2 * span.heapBytes();
    }

    /** Returns the chars the pass read. */
    long length() {
      return at;
    }

    /**
     * Returns the cost of the replacement until it is applied: the buffers, the inserted text as a
     * {@code String} and as the rope's leaves, and the removed text as a {@code String} for undo.
     */
    long finishedBytes() {
      RopeStorage text = built();
      return buffers
          + 2 * stringBytes(text, 0, text.length())
          + stringBytes(current, prefix, removedEnd());
    }

    Replacement replacement() {
      return new Replacement(prefix, removedEnd() - prefix, built().toString());
    }

    private int removedEnd() {
      return current.length() - run;
    }

    private RopeStorage built() {
      if (built == null) {
        built = span.build();
      }
      return built;
    }

    /** Returns the heap a {@code String} of a range takes: 2 bytes per char past Latin-1. */
    private static long stringBytes(TextContent text, int start, int end) {
      TextCursor cursor = text.cursor(start);
      int bits = 0;
      for (int i = start; i < end; i++) {
        bits |= cursor.next();
      }
      return (long) (end - start) * (bits > 0xff ? 2 : 1);
    }
  }
}
