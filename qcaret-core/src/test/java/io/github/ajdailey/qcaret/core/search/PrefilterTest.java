package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The literal prefilter: the literal a query yields, results equal to the matcher without it, and
 * the regex run only from the literal's occurrences. {@code -Dqcaret.prefilter.seed=N} runs one
 * seed of the equivalence test.
 */
class PrefilterTest {
  private static final CancellationToken LIVE = new CancellationToken();

  /** Pieces of generated text: literals, near misses, surrogate pairs and every line break. */
  private static final String[] PIECES = {
    "abc", "abcd", "abcx", "xabc", "xab", "xab😀c", "xabc", "xyz", "ab", "D", "d", "😀", "😀bc",
    "1", "22", " ", "\n", "\r\n", "\r", "x", "_", "a.b", "axb"
  };

  /** Queries as {text, regex, whole word}, all case-sensitive. */
  private static final Object[][] QUERIES = {
    {"abc", true, false},
    {"abc\\d+", true, false},
    {"abcd?x", true, false},
    {"abc|xyz", true, false},
    {"xab😀?c", true, false},
    {"abc(?=x)", true, false},
    {"abc(?<=xabc)", true, false},
    {"abc\\b", true, false},
    {"abc$", true, false},
    {"abc(\\w)\\1", true, false},
    {"abc.*?d", true, false},
    {"abc(?i)D", true, false},
    {"abc\\R", true, false},
    {"abc(?<=\\Gabc)", true, false},
    {"abc\\w*", true, true},
    {"abc", false, false},
    {"abc", false, true},
    {"a.b", false, false},
    {"😀bc", false, false},
    {"\ude00bc", false, false}
  };

  @TempDir Path temp;

  private static TextMatcher regex(String text) {
    return new SearchQuery(text, true, true, false).compile();
  }

  @Test
  void requiredPrefixStopsAtTheFirstMetacharacter() {
    String[][] cases = {
      {"abc", "abc"},
      {"abc|def", ""},
      {"abc(d|e)", ""},
      {"abcd?", "abc"},
      {"abcd*", "abc"},
      {"abcd{2}", "abc"},
      {"abcd+", "abcd"},
      {"abc.", "abc"},
      {"ab\\.cd", "ab"},
      {"\\Qabc\\E", ""},
      {"abc\\Qd\\E", "abc"},
      {"abc[de]f", "abc"},
      {"[a]bc", ""},
      {"(?i)abc", ""},
      {"abc(?i)def", "abc"},
      {"^abc", ""},
      {"abc$", "abc"},
      {"ab😀c", "ab😀c"},
      {"ab😀?", "ab"},
      {"😀😀*", "😀"},
      {"a?", ""}
    };
    for (String[] c : cases) {
      assertEquals(c[1], SearchQuery.requiredPrefix(c[0]), c[0]);
    }
  }

  @Test
  void onlyCaseSensitiveQueriesWithALongEnoughSafeLiteralArePrefiltered() {
    assertEquals("flag", regex("flag\\w+d").literal());
    assertEquals("a.b|c", new SearchQuery("a.b|c", false, true, false).compile().literal());
    assertEquals("abc", new SearchQuery("abc\\w", true, true, true).compile().literal());
    assertNull(new SearchQuery("flag\\w+d", true, false, false).compile().literal());
    assertNull(new SearchQuery("flagword", false, false, false).compile().literal());
    assertNull(regex("ab\\w").literal());
    assertNull(regex("abc(?<=\\Gabc)").literal());
    assertNull(new SearchQuery("\ude00bc", false, true, false).compile().literal());
    assertEquals("a\\Gb", new SearchQuery("a\\Gb", false, true, false).compile().literal());
  }

  @TestFactory
  List<DynamicTest> resultsEqualTheMatcherWithoutTheLiteral() {
    List<DynamicTest> tests = new ArrayList<DynamicTest>();
    String one = System.getProperty("qcaret.prefilter.seed");
    long first = one == null ? 1 : Long.parseLong(one);
    long last = one == null ? 40 : first;
    for (long seed = first; seed <= last; seed++) {
      final long s = seed;
      tests.add(
          DynamicTest.dynamicTest(
              "seed " + s,
              new Executable() {
                @Override
                public void execute() {
                  Random random = new Random(s);
                  String text = generate(random, 200 + random.nextInt(9000));
                  for (Object[] q : QUERIES) {
                    TextMatcher with =
                        new SearchQuery((String) q[0], (Boolean) q[1], true, (Boolean) q[2])
                            .compile();
                    compare(with, (String) q[0], text, random);
                    compare(with, (String) q[0], RopeStorage.of(text).snapshot(), random);
                  }
                }
              }));
    }
    return tests;
  }

  /** A file past every read, decode, scan and check block, with literals across each window. */
  @Test
  void aFileLargerThanEveryBlockMatchesAsWithoutTheLiteral() throws IOException {
    Random random = new Random(7);
    StringBuilder text = new StringBuilder(generate(random, 3 * FileSearch.BLOCK_BYTES + 1000));
    for (int at = TextMatcher.CHECK_CHARS; at + 8 < text.length(); at += TextMatcher.CHECK_CHARS) {
      int start = at - 1 - (at / TextMatcher.CHECK_CHARS) % 3;
      text.replace(start, start + 4, "abcd");
      if (Character.isHighSurrogate(text.charAt(start - 1))) {
        text.setCharAt(start - 1, 'x');
      }
      if (Character.isLowSurrogate(text.charAt(start + 4))) {
        text.setCharAt(start + 4, 'x');
      }
    }
    Path file = temp.resolve("large.txt");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    for (Object[] q : QUERIES) {
      TextMatcher with =
          new SearchQuery((String) q[0], (Boolean) q[1], true, (Boolean) q[2]).compile();
      FileSearch.Single a = FileSearch.searchFile(file, options(with), 1_000_000, LIVE);
      FileSearch.Single b =
          FileSearch.searchFile(file, options(with.withoutLiteral()), 1_000_000, LIVE);
      assertEquals(b.hits().outcome(), a.hits().outcome(), (String) q[0]);
      compare(with, (String) q[0], text.toString(), random);
    }
  }

  private static FileSearch.Options options(TextMatcher matcher) {
    return new FileSearch.Options(
        matcher, FileFilter.of("", "", "", false), StandardCharsets.UTF_8, 1 << 24, 500, null);
  }

  @Test
  void theRegexRunsOnlyFromTheLiteralsOccurrences() {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      text.append("lorem ipsum dolor sit amet ").append(i % 3 == 0 ? "return " : "retx ");
    }
    String s = text.toString();
    List<Integer> occurrences = new ArrayList<Integer>();
    for (int at = s.indexOf("ret"); at >= 0; at = s.indexOf("ret", at + 1)) {
      occurrences.add(at);
    }
    CharSequence[] texts = {s, RopeStorage.of(s).snapshot()};
    for (CharSequence t : texts) {
      final List<Integer> starts = new ArrayList<Integer>();
      TextMatcher m =
          regex("ret\\w+n")
              .reportingStarts(
                  new IntConsumer() {
                    @Override
                    public void accept(int offset) {
                      starts.add(offset);
                    }
                  });
      assertEquals(100, m.all(t, 0, t.length(), 1000, LIVE).size());
      assertEquals(occurrences, starts);
      starts.clear();
      assertEquals(100, m.replaceAll(t, m.template("x"), LIVE).size());
      assertEquals(occurrences, starts);
    }
  }

  @Test
  void aCanceledTokenStopsTheLiteralSearchOfAString() {
    final String text = "a line of mock text\n".repeat(10_000);
    final CancellationToken canceled = new CancellationToken();
    canceled.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            regex("zqx[xy]").all(text, 0, text.length(), 10, canceled);
          }
        });
  }

  private static String generate(Random random, int chars) {
    StringBuilder text = new StringBuilder(chars + 16);
    while (text.length() < chars) {
      text.append(PIECES[random.nextInt(PIECES.length)]);
    }
    return text.toString();
  }

  /** Compares every call of a matcher with the same matcher without its literal. */
  private static void compare(TextMatcher with, String query, CharSequence text, Random random) {
    TextMatcher without = with.withoutLiteral();
    int length = text.length();
    String label = query + " on " + text.getClass().getSimpleName();
    int[] offsets = {0, random.nextInt(length + 1), random.nextInt(length + 1), length};
    for (int at : offsets) {
      assertEquals(without.next(text, at, LIVE), with.next(text, at, LIVE), label);
      assertEquals(without.previous(text, at, LIVE), with.previous(text, at, LIVE), label);
    }
    assertEquals(
        without.previous(text, length + 1, LIVE), with.previous(text, length + 1, LIVE), label);
    int from = random.nextInt(length);
    int to = from + random.nextInt(length - from + 1);
    assertEquals(
        without.all(text, 0, length, Integer.MAX_VALUE, LIVE),
        with.all(text, 0, length, Integer.MAX_VALUE, LIVE),
        label);
    assertEquals(without.all(text, from, to, 5, LIVE), with.all(text, from, to, 5, LIVE), label);
    assertEquals(
        without.replaceAll(text, without.template("<$0>"), LIVE),
        with.replaceAll(text, with.template("<$0>"), LIVE),
        label);
    int[] ranges = {from, to};
    int[] limits = {3, Integer.MAX_VALUE - 1};
    for (int limit : limits) {
      assertEquals(
          TextSearch.search(without, text, null, limit, 60, LIVE),
          TextSearch.search(with, text, null, limit, 60, LIVE),
          label);
      assertEquals(
          TextSearch.search(without, text, ranges, limit, 60, LIVE),
          TextSearch.search(with, text, ranges, limit, 60, LIVE),
          label);
    }
  }
}
