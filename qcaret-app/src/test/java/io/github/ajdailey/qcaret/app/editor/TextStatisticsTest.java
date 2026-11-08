package io.github.ajdailey.qcaret.app.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.buffer.RopeStorage;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TextStatisticsTest {
  private static final LongConsumer IGNORE =
      new LongConsumer() {
        @Override
        public void accept(long read) {
          // Progress is not checked here.
        }
      };

  private static TextStatistics count(String text, Charset charset) {
    TextContent content = RopeStorage.of(text);
    return TextStatistics.count(
        content, 0, content.length(), charset, new CancellationToken(), IGNORE);
  }

  @Test
  void anEmptyTextIsOneLineAndNothingElse() {
    assertEquals(new TextStatistics(1, 0, 0, 0, 0), count("", StandardCharsets.UTF_8));
  }

  @Test
  void wordsAreRunsOfNonWhitespaceAndBreaksCountOnceEach() {
    // CR LF is one break and two chars; a no-break space joins two words.
    String text = "one two\r\nthree\u00a0four\tfive\rsix\n";
    TextStatistics s = count(text, StandardCharsets.UTF_8);
    assertEquals(4, s.lines());
    assertEquals(5, s.words());
    assertEquals(text.length(), s.chars());
    assertEquals(text.length() - 6, s.nonSpaceChars());
    assertEquals(text.getBytes(StandardCharsets.UTF_8).length, s.bytes());
  }

  @Test
  void aSurrogatePairIsOneCharAndALoneSurrogateIsReplaced() {
    String text = "a\ud83d\ude00b \ud800";
    TextStatistics s = count(text, StandardCharsets.UTF_8);
    assertEquals(5, s.chars());
    assertEquals(2, s.words());
    // a, the 4-byte emoji, b, space, then '?' for the lone high surrogate.
    assertEquals(1 + 4 + 1 + 1 + 1, s.bytes());
    assertEquals(2 * text.length(), count(text, StandardCharsets.UTF_16LE).bytes());
  }

  /** Builds a text longer than every rope leaf and encode buffer, pairs straddling both. */
  private static String large() {
    StringBuilder b = new StringBuilder();
    int i = 0;
    while (b.length() < 5 * TextStatistics.Counter.BUFFER_CHARS + 7) {
      b.append("word").append(i).append(i % 7 == 0 ? "\r\n" : " ");
      b.append(i % 3 == 0 ? "\ud83d\ude00" : "\u65e5\u672c");
      b.append(i % 11 == 0 ? "\n" : "\u00e9 ");
      i++;
    }
    return b.toString();
  }

  private static TextStatistics expected(String text, Charset charset) {
    long breaks = 0;
    long words = 0;
    long nonSpace = 0;
    boolean inWord = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\r' || c == '\n' && (i == 0 || text.charAt(i - 1) != '\r')) {
        breaks++;
      }
    }
    int[] points = text.codePoints().toArray();
    for (int cp : points) {
      if (Character.isWhitespace(cp)) {
        inWord = false;
      } else {
        nonSpace++;
        if (!inWord) {
          words++;
        }
        inWord = true;
      }
    }
    return new TextStatistics(
        breaks + 1, words, points.length, nonSpace, text.getBytes(charset).length);
  }

  @Test
  void aTextLargerThanEveryBlockCountsAsAString() {
    String text = large();
    List<Charset> charsets = new ArrayList<Charset>();
    charsets.add(StandardCharsets.UTF_8);
    charsets.add(StandardCharsets.UTF_16BE);
    charsets.add(Charset.forName("UTF-32LE"));
    charsets.add(Charset.forName("GB18030"));
    charsets.add(Charset.forName("Shift_JIS"));
    charsets.add(StandardCharsets.ISO_8859_1);
    for (Charset charset : charsets) {
      assertEquals(expected(text, charset), count(text, charset), charset.name());
    }
  }

  @Test
  void piecesSplitAnywhereCountAsOneText() {
    String text = large();
    Charset charset = Charset.forName("Shift_JIS");
    TextStatistics.Counter counter = new TextStatistics.Counter(charset);
    for (int at = 0; at < text.length(); at += 1 + at % 5) {
      counter.add(text, at, Math.min(text.length(), at + 1 + at % 5));
    }
    assertEquals(expected(text, charset), counter.finish());
  }

  @Test
  void progressReachesTheRangeLength() {
    String text = large();
    TextContent content = RopeStorage.of(text);
    final long[] last = {0};
    TextStatistics.count(
        content,
        3,
        content.length() - 2,
        StandardCharsets.UTF_8,
        new CancellationToken(),
        new LongConsumer() {
          @Override
          public void accept(long read) {
            last[0] = read;
          }
        });
    assertEquals(content.length() - 5, last[0]);
  }

  @Test
  void aCanceledCountThrows() {
    final TextContent content = RopeStorage.of(large());
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            TextStatistics.count(
                content, 0, content.length(), StandardCharsets.UTF_8, token, IGNORE);
          }
        });
  }

  @Test
  void sumsAddEachCount() {
    TextStatistics a = new TextStatistics(1, 2, 3, 4, 5);
    assertEquals(new TextStatistics(2, 4, 6, 8, 13), a.plus(a).withExtraBytes(3));
  }
}
