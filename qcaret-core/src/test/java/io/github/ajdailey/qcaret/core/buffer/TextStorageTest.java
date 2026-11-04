package io.github.ajdailey.qcaret.core.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Randomized edits on {@link RopeStorage}, checked against a {@link StringBuilder}. Each case is
 * named by its line endings and seed; {@code -Dqcaret.storage.seed=N} runs one seed.
 */
class TextStorageTest {
  private static final int SEEDS = 12;

  /** Line endings of the initial text and of inserted text. */
  enum Endings {
    /** Every alphabet char, breaks included, drawn at random. */
    RANDOM("abcdefgh  \t\n\n\r\ré中"),
    /** CR LF only. */
    CRLF("abcdefgh  \té中"),
    /** LF, CR LF and CR mixed. */
    MIXED("abcdefgh  \té中");

    final String alphabet;

    Endings(String alphabet) {
      this.alphabet = alphabet;
    }

    String text(SplittableRandom random, int length) {
      StringBuilder out = new StringBuilder(length);
      while (out.length() < length) {
        if (this != RANDOM && random.nextInt(12) == 0) {
          int kind = this == CRLF ? 1 : random.nextInt(3);
          out.append(kind == 0 ? "\n" : kind == 1 ? "\r\n" : "\r");
        } else {
          out.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
      }
      out.setLength(length);
      return out.toString();
    }
  }

  static List<Arguments> cases() {
    List<Long> seeds = new ArrayList<>();
    String one = System.getProperty("qcaret.storage.seed");
    if (one != null) {
      seeds.add(Long.parseLong(one));
    } else {
      for (long s = 1; s <= SEEDS; s++) {
        seeds.add(s);
      }
    }
    List<Arguments> out = new ArrayList<>();
    for (Endings endings : Endings.values()) {
      for (Long seed : seeds) {
        out.add(Arguments.of(endings, seed));
      }
    }
    return out;
  }

  @ParameterizedTest(name = "{0} seed={1}")
  @MethodSource("cases")
  void randomEditsMatchTheOracle(Endings endings, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    // Every fourth seed starts with 69 leaves and a CR LF across the boundary after leaf 32.
    int initial = seed % 4 == 0 ? 140_000 : random.nextInt(2_000);
    StringBuilder oracle = new StringBuilder(endings.text(random, initial));
    if (initial > RopeStorage.LEAF_MAX) {
      int leaves = (initial + RopeStorage.LEAF_MAX - 1) / RopeStorage.LEAF_MAX;
      int boundary = (int) ((long) initial * (leaves / 2) / leaves);
      oracle.setCharAt(boundary - 1, '\r');
      oracle.setCharAt(boundary, '\n');
    }
    TextStorage storage = RopeStorage.of(oracle);
    verify(storage, oracle.toString(), random);
    List<TextSnapshot> snapshots = new ArrayList<>();
    List<String> expected = new ArrayList<>();
    int ops = initial > 10_000 ? 400 : 2_000;
    for (int op = 0; op < ops; op++) {
      int len = oracle.length();
      int offset = random.nextInt(len + 1);
      int remove = random.nextInt(4) == 0 ? 0 : random.nextInt(Math.min(len - offset, 40) + 1);
      int insertLength = random.nextInt(3) == 0 ? 0 : random.nextInt(30);
      if (random.nextInt(400) == 0) {
        insertLength = 4_000 + random.nextInt(6_000);
      }
      String insert = endings.text(random, insertLength);
      storage.replace(offset, remove, insert);
      oracle.replace(offset, offset + remove, insert);
      assertEquals(oracle.length(), storage.length());
      if (op % 199 == 0) {
        verify(storage, oracle.toString(), random);
      }
      if (snapshots.size() < 5 && random.nextInt(50) == 0) {
        snapshots.add(storage.snapshot());
        expected.add(oracle.toString());
      }
    }
    verify(storage, oracle.toString(), random);
    for (int i = 0; i < snapshots.size(); i++) {
      verify(snapshots.get(i), expected.get(i), random);
    }
  }

  @Test
  void rangesAreChecked() {
    final TextStorage storage = RopeStorage.of("ab\ncd");
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            storage.replace(4, 2, "x");
          }
        });
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            storage.lineAt(6);
          }
        });
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            storage.offsetOfLine(2);
          }
        });
    assertThrows(
        IndexOutOfBoundsException.class,
        new Executable() {
          @Override
          public void execute() {
            storage.cursor(6);
          }
        });
    storage.replace(0, 5, "");
    assertEquals(1, storage.lineCount());
    assertEquals(0, storage.lineAt(0));
    assertEquals("", storage.line(0));
    assertEquals(0, storage.lineView(0, new LineView()).length());
    final TextCursor cursor = storage.cursor(0);
    assertFalse(cursor.hasNext());
    assertFalse(cursor.hasPrevious());
    assertThrows(
        NoSuchElementException.class,
        new Executable() {
          @Override
          public void execute() {
            cursor.next();
          }
        });
  }

  /**
   * Checks content, chunks, cursors, every line and every offset of {@code text} against {@code
   * expected}.
   */
  private static void verify(TextContent text, String expected, SplittableRandom random) {
    assertEquals(expected, text.toString());
    int len = expected.length();
    List<Integer> starts = new ArrayList<>();
    starts.add(0);
    for (int i = 1; i <= len; i++) {
      char c = expected.charAt(i - 1);
      if (c == '\n' || (c == '\r' && (i == len || expected.charAt(i) != '\n'))) {
        starts.add(i);
      }
    }
    assertEquals(starts.size(), text.lineCount());
    LineView view = new LineView();
    for (int line = 0; line < starts.size(); line++) {
      int start = starts.get(line);
      assertEquals(start, text.offsetOfLine(line), "offsetOfLine " + line);
      int end = line + 1 < starts.size() ? starts.get(line + 1) : len;
      String raw = expected.substring(start, end);
      int bodyEnd = raw.length();
      if (bodyEnd > 0 && raw.charAt(bodyEnd - 1) == '\n') {
        bodyEnd--;
      }
      if (bodyEnd > 0 && raw.charAt(bodyEnd - 1) == '\r') {
        bodyEnd--;
      }
      String body = raw.substring(0, bodyEnd);
      assertEquals(body, text.line(line), "line " + line);
      assertEquals(body.length(), text.lineLength(line), "lineLength " + line);
      text.lineView(line, view);
      assertEquals(start, view.start(), "lineView start " + line);
      assertEquals(body.length(), view.length(), "lineView length " + line);
      for (int i = 0; i < body.length(); i++) {
        assertEquals(body.charAt(i), view.charAt(i), "lineView " + line + " char " + i);
      }
    }
    int line = 0;
    for (int offset = 0; offset <= len; offset++) {
      while (line + 1 < starts.size() && starts.get(line + 1) <= offset) {
        line++;
      }
      assertEquals(line, text.lineAt(offset), "lineAt " + offset);
    }
    TextCursor forward = text.cursor(0);
    for (int i = 0; i < len; i++) {
      assertEquals(expected.charAt(i), forward.next(), "cursor next " + i);
    }
    assertFalse(forward.hasNext());
    for (int i = len - 1; i >= 0; i--) {
      assertEquals(expected.charAt(i), forward.previous(), "cursor previous " + i);
    }
    for (int i = 0; i < 20; i++) {
      int a = random.nextInt(len + 1);
      int b = a + random.nextInt(len - a + 1);
      final StringBuilder sb = new StringBuilder();
      text.readChunks(
          a,
          b,
          new ChunkVisitor() {
            @Override
            public void chunk(CharSequence chunk, int s, int e) {
              sb.append(chunk, s, e);
            }
          });
      assertEquals(expected.substring(a, b), sb.toString(), "readChunks " + a + ".." + b);
      assertEquals(expected.substring(a, b), text.subSequence(a, b).toString());
      if (a < len) {
        assertEquals(expected.charAt(a), text.charAt(a), "charAt " + a);
      }
      forward.seek(a);
      assertEquals(a, forward.position());
      if (a < len) {
        assertEquals(expected.charAt(a), forward.next(), "cursor after seek " + a);
      }
    }
  }
}
