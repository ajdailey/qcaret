package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The line-break scans of a {@link String}: {@link TextSearch#search} on a {@code String} and on a
 * {@link TextSearch.Prefix} of one gives the same {@link TextSearch.Outcome} as on other text, read
 * char by char, over 40 seeded texts with every break form, breaks at each side of a {@link
 * TextMatcher#CHECK_CHARS} window end, and texts past {@link TextSearch#SCAN_CHARS}. A cancel stops
 * each scan within one window, counted through {@link TextSearch#setProbe}. Without a limit, the
 * skipped-line count equals a count taken line by line in the test. {@code
 * -Dqcaret.linescan.seed=N} runs one seed.
 */
class LineScanTest {
  private static final String[] BREAKS = {"\n", "\r", "\r\n", "\r\r\n", "\n\r", "\n\n"};

  private static final List<String> QUERIES =
      List.of("needle", "\\r|\\n", "^.", "e[^\\r\\n]*$", "(?s)needle.{0,3}", "le\\Rne");

  @AfterEach
  void close() {
    TextSearch.setProbe(null);
  }

  /** Text read char by char, as a rope is. */
  private static final class Chars implements CharSequence {
    private final String text;

    Chars(String text) {
      this.text = text;
    }

    @Override
    public int length() {
      return text.length();
    }

    @Override
    public char charAt(int index) {
      return text.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      return text.subSequence(start, end);
    }

    @Override
    public String toString() {
      return text;
    }
  }

  /** Lines of random length with random breaks, and breaks placed around window ends. */
  private static String text(long seed) {
    Random random = new Random(seed);
    int length = seed % 4 == 0 ? 3 * TextSearch.SCAN_CHARS : 6 * TextMatcher.CHECK_CHARS;
    StringBuilder text = new StringBuilder();
    if (random.nextBoolean()) {
      text.append(BREAKS[random.nextInt(BREAKS.length)]);
    }
    while (text.length() < length) {
      int window = TextMatcher.CHECK_CHARS;
      int toEnd = window - text.length() % window;
      if (toEnd <= 2 && random.nextBoolean()) {
        text.append(BREAKS[random.nextInt(BREAKS.length)]);
        continue;
      }
      int chars = random.nextInt(10) == 0 ? random.nextInt(3000) : random.nextInt(80);
      for (int i = 0; i < chars; i++) {
        text.append(random.nextInt(30) == 0 ? "needle" : (char) ('a' + random.nextInt(26)));
      }
      text.append(BREAKS[random.nextInt(BREAKS.length)]);
    }
    if (random.nextBoolean()) {
      text.append(random.nextBoolean() ? "\r" : "needle");
    }
    return text.toString();
  }

  /**
   * Counts the lines over {@code max} chars whose chars or break lie in a range, each once, reading
   * the text line by line.
   */
  private static int skipped(String text, int[] ranges, int max) {
    int[] bounds = ranges == null ? new int[] {0, text.length()} : ranges;
    int count = 0;
    int start = 0;
    while (start < text.length()) {
      int end = start;
      while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
        end++;
      }
      int next = end;
      if (next < text.length()) {
        next += text.startsWith("\r\n", next) ? 2 : 1;
      }
      if (end - start > max) {
        for (int r = 0; r + 1 < bounds.length; r += 2) {
          if (bounds[r] < bounds[r + 1] && start < bounds[r + 1] && next > bounds[r]) {
            count++;
            break;
          }
        }
      }
      start = next;
    }
    return count;
  }

  private static void assertSame(
      String text, TextMatcher matcher, int[] ranges, int limit, int max, String name) {
    CancellationToken token = new CancellationToken();
    TextSearch.Outcome expected =
        TextSearch.search(matcher, new Chars(text), ranges, limit, max, token);
    if (!expected.limited() && expected.tooComplexLine() < 0) {
      assertEquals(skipped(text, ranges, max), expected.skippedLines(), name + " skipped");
    }
    assertEquals(
        expected, TextSearch.search(matcher, text, ranges, limit, max, token), name + " string");
    assertEquals(
        expected,
        TextSearch.search(
            matcher, new TextSearch.Prefix(text, text.length()), ranges, limit, max, token),
        name + " prefix");
  }

  private static void check(long seed) {
    String text = text(seed);
    Random random = new Random(seed);
    int[] maxes = {Integer.MAX_VALUE, 1, 200, TextMatcher.CHECK_CHARS, 1 + random.nextInt(3000)};
    int[] ranges = new int[6];
    for (int i = 0; i < ranges.length; i++) {
      ranges[i] = random.nextInt(text.length() + 1);
    }
    Arrays.sort(ranges);
    for (String query : QUERIES) {
      TextMatcher matcher = new SearchQuery(query, true, true, false).compile();
      for (int max : maxes) {
        String name = "seed " + seed + " query " + query + " max " + max;
        assertSame(text, matcher, null, Integer.MAX_VALUE, max, name);
        assertSame(text, matcher, ranges, Integer.MAX_VALUE, max, name + " ranges");
        assertSame(text, matcher, null, 7, max, name + " limit");
      }
    }
  }

  @Test
  void aStringScanGivesTheOutcomeOfACharByCharScan() {
    String one = System.getProperty("qcaret.linescan.seed");
    if (one != null) {
      check(Long.parseLong(one));
      return;
    }
    for (long seed = 0; seed < 40; seed++) {
      check(seed);
    }
  }

  @Test
  void breaksAtEachSideOfAWindowEnd() {
    int end = TextMatcher.CHECK_CHARS;
    for (String brk : BREAKS) {
      for (int at = end - 3; at <= end + 1; at++) {
        String text = "needle".repeat(at / 6) + "x".repeat(at % 6) + brk + "needle" + brk;
        for (String query : QUERIES) {
          TextMatcher matcher = new SearchQuery(query, true, true, false).compile();
          String name = "break " + brk.replace("\r", "CR").replace("\n", "LF") + " at " + at;
          assertSame(text, matcher, null, Integer.MAX_VALUE, Integer.MAX_VALUE, name);
          assertSame(text, matcher, null, Integer.MAX_VALUE, at - 1, name + " long");
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(TextSearch.Scan.class)
  void aCancelStopsEachScanWithinOneWindow(final TextSearch.Scan scan) {
    final String text = "a line of mock text\n".repeat(20 * TextMatcher.CHECK_CHARS / 20);
    final CancellationToken token = new CancellationToken();
    final AtomicInteger windows = new AtomicInteger();
    final int cancelAt = 3;
    TextSearch.setProbe(
        new TextSearch.Probe() {
          @Override
          public void window(TextSearch.Scan at, int start) {
            if (at == scan && windows.incrementAndGet() == cancelAt) {
              token.cancel();
            }
          }
        });
    final TextMatcher matcher = new SearchQuery("zq[xy]", true, true, false).compile();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            TextSearch.search(matcher, text, null, 100, Integer.MAX_VALUE, token);
          }
        });
    int after = windows.get() - cancelAt;
    assertTrue(after >= 0 && after <= 1, scan + " windows after: " + after);
  }
}
