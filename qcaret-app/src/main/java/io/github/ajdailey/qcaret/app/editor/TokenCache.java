package io.github.ajdailey.qcaret.app.editor;

import io.github.ajdailey.qcaret.app.theme.TokenRole;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.document.TextEdit;
import io.github.ajdailey.qcaret.core.syntax.TokenType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tokens of recently painted lines, least recently used dropped first, shifted through each
 * document change so the old tokens paint until new ones arrive. Confined to the UI thread.
 *
 * <ul>
 *   <li>An edit without line breaks moves the tokens after it within its line, and a token holding
 *       the edit grows or shrinks with it.
 *   <li>An insert with line breaks keeps the tokens before it on its line and moves the ones after
 *       it to the last inserted line.
 *   <li>Any other edit drops the lines it touches. Lines below an edit move by its line delta.
 *   <li>A change whose line count differs from the sum of its edits' deltas, where a CR and an LF
 *       join or part at an edit's end, drops every line.
 * </ul>
 */
public final class TokenCache {
  /** Default line count kept. */
  public static final int DEFAULT_CAPACITY = 2000;

  private static final int[] ROLES = roles();

  private final int capacity;
  private LinkedHashMap<Integer, int[]> lines;

  /**
   * Creates an empty cache.
   *
   * @param capacity the lines kept
   */
  public TokenCache(int capacity) {
    this.capacity = capacity;
    this.lines = newMap();
  }

  /**
   * Returns the role a token type paints with.
   *
   * @param type a {@link TokenType} ordinal
   * @return the {@link TokenRole} ordinal
   */
  public static int role(int type) {
    return ROLES[type];
  }

  /**
   * Converts engine tokens to role tokens.
   *
   * @param tokens {@code start, end, type} triples
   * @return {@code start, end, role} triples
   */
  public static int[] toRoles(int[] tokens) {
    int[] out = tokens.clone();
    for (int i = 2; i < out.length; i += 3) {
      out[i] = ROLES[out[i]];
    }
    return out;
  }

  private static int[] roles() {
    TokenType[] types = TokenType.values();
    int[] out = new int[types.length];
    for (int i = 0; i < types.length; i++) {
      out[i] = TokenRole.valueOf(types[i].name()).ordinal();
    }
    return out;
  }

  private LinkedHashMap<Integer, int[]> newMap() {
    return new Lru(capacity);
  }

  /** Lines in access order, dropping the eldest past the capacity. */
  private static final class Lru extends LinkedHashMap<Integer, int[]> {
    private static final long serialVersionUID = 1L;

    private final int capacity;

    Lru(int capacity) {
      super(capacity * 4 / 3 + 1, 0.75f, true);
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest) {
      return size() > capacity;
    }
  }

  /**
   * Returns a line's tokens and marks it used.
   *
   * @param line the line
   * @return role triples, or null
   */
  public int[] get(int line) {
    return lines.get(line);
  }

  /**
   * Stores a line's tokens.
   *
   * @param line the line
   * @param tokens role triples
   */
  public void put(int line, int[] tokens) {
    lines.put(line, tokens);
  }

  /**
   * Drops a range of lines.
   *
   * @param from the first line
   * @param to the last line, inclusive
   */
  public void remove(int from, int to) {
    if (to - from < lines.size()) {
      for (int line = from; line <= to; line++) {
        lines.remove(line);
      }
      return;
    }
    List<Integer> keys = new ArrayList<Integer>(lines.keySet());
    for (Integer line : keys) {
      if (line >= from && line <= to) {
        lines.remove(line);
      }
    }
  }

  /**
   * Returns the lines held, for reading on another thread; the token arrays are shared and never
   * modified.
   *
   * @return a copy of the entries, without the use order
   */
  public TokenSource copy() {
    final Map<Integer, int[]> copy = new HashMap<Integer, int[]>(lines);
    return new TokenSource() {
      @Override
      public int[] tokens(int line) {
        return copy.get(line);
      }
    };
  }

  /** Drops every line. */
  public void clear() {
    lines.clear();
  }

  /**
   * Returns the lines held.
   *
   * @return the count
   */
  public int size() {
    return lines.size();
  }

  /**
   * Shifts the lines through a change.
   *
   * @param edits the change's edits, ascending, in the coordinates of the text before it
   * @param before the text before the change
   * @param linesAfter the line count after it
   */
  public void apply(List<TextEdit> edits, TextContent before, int linesAfter) {
    int total = 0;
    for (TextEdit e : edits) {
      total += e.insertedLines() - e.removedLines();
    }
    if (before.lineCount() + total != linesAfter) {
      lines.clear();
      return;
    }
    for (int i = edits.size() - 1; i >= 0; i--) {
      TextEdit e = edits.get(i);
      int line = e.startLine();
      int column = e.offset() - before.offsetOfLine(line);
      int removed = e.removed().length();
      int[] tokens = lines.get(line);
      if (e.removedLines() == 0 && e.insertedLines() == 0) {
        if (tokens != null) {
          lines.put(line, edited(tokens, column, removed, e.inserted().length()));
        }
        continue;
      }
      shift(line, e.removedLines(), e.insertedLines() - e.removedLines());
      lines.remove(line);
      if (e.removedLines() == 0 && tokens != null) {
        String inserted = e.inserted();
        int last = Math.max(inserted.lastIndexOf('\n'), inserted.lastIndexOf('\r')) + 1;
        lines.put(line, head(tokens, column));
        lines.put(
            line + e.insertedLines(), tail(tokens, column + removed, inserted.length() - last));
      }
    }
  }

  /** Drops lines {@code line + 1} to {@code line + removed} and moves the lines after them. */
  private void shift(int line, int removed, int delta) {
    LinkedHashMap<Integer, int[]> next = newMap();
    for (Map.Entry<Integer, int[]> entry : lines.entrySet()) {
      int key = entry.getKey();
      if (key <= line) {
        next.put(key, entry.getValue());
      } else if (key > line + removed) {
        next.put(key + delta, entry.getValue());
      }
    }
    lines = next;
  }

  /** Returns tokens after replacing {@code removed} chars at a column with {@code inserted}. */
  static int[] edited(int[] tokens, int column, int removed, int inserted) {
    int end = column + removed;
    int delta = inserted - removed;
    int[] out = new int[tokens.length];
    int size = 0;
    for (int i = 0; i < tokens.length; i += 3) {
      int start = tokens[i];
      int stop = tokens[i + 1];
      if (stop <= column) {
        // Before the edit.
      } else if (start >= end) {
        start += delta;
        stop += delta;
      } else {
        start = Math.min(start, column);
        stop = Math.max(column + inserted, stop + delta);
      }
      if (stop > start) {
        out[size++] = start;
        out[size++] = stop;
        out[size++] = tokens[i + 2];
      }
    }
    return Arrays.copyOf(out, size);
  }

  /** Returns the tokens before a column, the last one cut there. */
  static int[] head(int[] tokens, int column) {
    int[] out = new int[tokens.length];
    int size = 0;
    for (int i = 0; i < tokens.length && tokens[i] < column; i += 3) {
      out[size++] = tokens[i];
      out[size++] = Math.min(tokens[i + 1], column);
      out[size++] = tokens[i + 2];
    }
    return Arrays.copyOf(out, size);
  }

  /** Returns the tokens from a column on, moved so that column lands at {@code at}. */
  static int[] tail(int[] tokens, int column, int at) {
    int[] out = new int[tokens.length];
    int size = 0;
    for (int i = 0; i < tokens.length; i += 3) {
      if (tokens[i + 1] <= column) {
        continue;
      }
      out[size++] = Math.max(tokens[i], column) - column + at;
      out[size++] = tokens[i + 1] - column + at;
      out[size++] = tokens[i + 2];
    }
    return Arrays.copyOf(out, size);
  }
}
