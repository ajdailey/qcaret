package io.github.ajdailey.qcaret.core.buffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Randomized edits on every {@link TextStorage} candidate, checked against a {@link StringBuilder}.
 * Each case is named by candidate and seed; {@code -Dqcaret.storage.seed=N} runs one seed.
 */
class TextStorageTest {
  private static final int SEEDS = 12;
  private static final String ALPHABET = "abcdefgh  \t\n\n\r\ré中";

  /** Creates a candidate from initial text. */
  interface Factory {
    TextStorage create(CharSequence text);
  }

  static List<Arguments> cases() {
    Factory[] factories = {
      new Factory() {
        @Override
        public TextStorage create(CharSequence text) {
          return PieceTreeStorage.of(text);
        }

        @Override
        public String toString() {
          return "pieceTree";
        }
      },
      new Factory() {
        @Override
        public TextStorage create(CharSequence text) {
          return RopeStorage.of(text);
        }

        @Override
        public String toString() {
          return "rope";
        }
      },
      new Factory() {
        @Override
        public TextStorage create(CharSequence text) {
          return GapBufferStorage.of(text);
        }

        @Override
        public String toString() {
          return "gapBuffer";
        }
      }
    };
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
    for (Factory f : factories) {
      for (Long seed : seeds) {
        out.add(Arguments.of(f, seed));
      }
    }
    return out;
  }

  @ParameterizedTest(name = "{0} seed={1}")
  @MethodSource("cases")
  void randomEditsMatchTheOracle(Factory factory, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    // Every fourth seed starts above two 64 Ki chunks, with a CR LF across the first boundary.
    int initial = seed % 4 == 0 ? 140_000 : random.nextInt(2_000);
    StringBuilder oracle = new StringBuilder(text(random, initial));
    if (initial > 65_537) {
      oracle.setCharAt(65_535, '\r');
      oracle.setCharAt(65_536, '\n');
    }
    TextStorage storage = factory.create(oracle);
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
      String insert = text(random, insertLength);
      storage.replace(offset, remove, insert);
      oracle.replace(offset, offset + remove, insert);
      assertEquals(oracle.length(), storage.length());
      if (op % 199 == 0) {
        verify(storage, oracle.toString(), random);
      }
      if (random.nextInt(50) == 0) {
        snapshots.add(storage.snapshot());
        expected.add(oracle.toString());
      }
    }
    verify(storage, oracle.toString(), random);
    for (int i = 0; i < snapshots.size(); i++) {
      verify(snapshots.get(i), expected.get(i), random);
    }
  }

  @ParameterizedTest(name = "{0} seed={1}")
  @MethodSource("cases")
  void rangesAreChecked(final Factory factory, long seed) {
    final TextStorage storage = factory.create("ab\ncd");
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
    storage.replace(0, 5, "");
    assertEquals(1, storage.lineCount());
    assertEquals(0, storage.lineAt(0));
    assertEquals("", storage.line(0));
  }

  private static String text(SplittableRandom random, int length) {
    char[] out = new char[length];
    for (int i = 0; i < length; i++) {
      out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
    }
    return new String(out);
  }

  /**
   * Checks content, chunks, every line and every offset of {@code text} against {@code expected}.
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
    for (int line = 0; line < starts.size(); line++) {
      int start = starts.get(line);
      assertEquals(start, text.offsetOfLine(line), "offsetOfLine " + line);
      int end = line + 1 < starts.size() ? starts.get(line + 1) : len;
      String raw = expected.substring(start, end);
      String body = raw.replaceFirst("(\r\n|\r|\n)$", "");
      assertEquals(body, text.line(line), "line " + line);
      assertEquals(body.length(), text.lineLength(line), "lineLength " + line);
    }
    int line = 0;
    for (int offset = 0; offset <= len; offset++) {
      while (line + 1 < starts.size() && starts.get(line + 1) <= offset) {
        line++;
      }
      assertEquals(line, text.lineAt(offset), "lineAt " + offset);
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
    }
  }
}
