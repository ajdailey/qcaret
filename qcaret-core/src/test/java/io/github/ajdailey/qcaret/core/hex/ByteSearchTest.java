package io.github.ajdailey.qcaret.core.hex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ByteSearchTest {
  private static final int WINDOW = ByteSearch.WINDOW;

  private static long find(
      HexSource source, HexPattern pattern, long from, boolean forward, boolean wrap)
      throws IOException {
    return ByteSearch.find(source, pattern, from, forward, wrap, new CancellationToken(), null);
  }

  /** Bytes from a four-letter alphabet, so short patterns match often. */
  private static byte[] small(int size, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    byte[] bytes = new byte[size];
    for (int i = 0; i < size; i++) {
      bytes[i] = (byte) ('a' + random.nextInt(4));
    }
    return bytes;
  }

  private static boolean matchesAt(byte[] data, HexPattern pattern, int at) {
    return at + pattern.length() <= data.length && pattern.matches(data, at);
  }

  @Test
  void aMatchAcrossAWindowBoundaryIsFound() throws IOException, HexPatternException {
    byte[] data = new byte[3 * WINDOW];
    HexPattern pattern = HexPattern.hex("DE AD BE EF");
    int[] plants = {WINDOW - 2};
    data[plants[0]] = (byte) 0xde;
    data[plants[0] + 1] = (byte) 0xad;
    data[plants[0] + 2] = (byte) 0xbe;
    data[plants[0] + 3] = (byte) 0xef;
    HexFixtures.ArraySource source = new HexFixtures.ArraySource(data);
    assertEquals(plants[0], find(source, pattern, 0, true, false));
    assertEquals(plants[0], find(source, pattern, data.length - 1, false, false));
    assertEquals(-1, find(source, pattern, plants[0] + 1, true, false));
    assertEquals(plants[0], find(source, pattern, plants[0] + 1, true, true));
    assertEquals(-1, find(source, pattern, plants[0] - 1, false, false));
    assertEquals(plants[0], find(source, pattern, plants[0] - 1, false, true));
  }

  @Test
  void everyStartAgreesWithACheckAtEachOffset() throws IOException, HexPatternException {
    byte[] data = small(2 * WINDOW + 300, 59);
    HexFixtures.ArraySource source = new HexFixtures.ArraySource(data);
    String[] patterns = {"61 62 63 64 61", "61 ?? ?? 64 64 62", "?? 63 63 63 63 63"};
    SplittableRandom random = new SplittableRandom(59);
    for (String text : patterns) {
      HexPattern pattern = HexPattern.hex(text);
      for (int k = 0; k < 40; k++) {
        int[] edges = {0, WINDOW, data.length - 1, data.length};
        int from = k < edges.length ? edges[k] : random.nextInt(data.length);
        long forward = -1;
        for (int at = from; at < data.length && forward < 0; at++) {
          forward = matchesAt(data, pattern, at) ? at : -1;
        }
        long wrapped = forward;
        for (int at = 0; at < from && wrapped < 0; at++) {
          wrapped = matchesAt(data, pattern, at) ? at : -1;
        }
        long backward = -1;
        for (int at = Math.min(from, data.length - 1); at >= 0 && backward < 0; at--) {
          backward = matchesAt(data, pattern, at) ? at : -1;
        }
        long backWrapped = backward;
        for (int at = data.length - 1; at > from && backWrapped < 0; at--) {
          backWrapped = matchesAt(data, pattern, at) ? at : -1;
        }
        String label = text + " from " + from;
        assertEquals(forward, find(source, pattern, from, true, false), label);
        assertEquals(wrapped, find(source, pattern, from, true, true), label);
        assertEquals(backward, find(source, pattern, from, false, false), label);
        assertEquals(backWrapped, find(source, pattern, from, false, true), label);
      }
    }
  }

  @Test
  void aPatternLongerThanTheSourceFindsNothing() throws IOException, HexPatternException {
    HexFixtures.ArraySource source = new HexFixtures.ArraySource(new byte[] {1, 2});
    assertEquals(-1, find(source, HexPattern.hex("01 02 03"), 0, true, true));
    assertEquals(0, find(source, HexPattern.hex("01 02"), 1, true, true));
    assertEquals(0, find(source, HexPattern.hex("01 02"), 5, false, false));
  }

  @Test
  void progressCountsEveryStartAndACancelStopsTheSearch() throws HexPatternException {
    final HexFixtures.ArraySource source = new HexFixtures.ArraySource(new byte[3 * WINDOW + 10]);
    final HexPattern pattern = HexPattern.hex("FF FF");
    final long[] last = new long[1];
    final CancellationToken token = new CancellationToken();
    try {
      assertEquals(
          -1,
          ByteSearch.find(
              source,
              pattern,
              0,
              true,
              true,
              token,
              new LongConsumer() {
                @Override
                public void accept(long examined) {
                  last[0] = examined;
                }
              }));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    assertEquals(3L * WINDOW + 9, last[0]);
    source.reads = 0;
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            ByteSearch.find(
                source,
                pattern,
                0,
                true,
                true,
                token,
                new LongConsumer() {
                  @Override
                  public void accept(long examined) {
                    token.cancel();
                  }
                });
          }
        });
    assertEquals(1, source.reads);
  }

  @Test
  void offsetsPastFourGibibytesAreFound() throws IOException, HexPatternException {
    HexFixtures.HugeSource source = new HexFixtures.HugeSource();
    long at = HexFixtures.HugeSource.SIZE - 100;
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < 8; i++) {
      text.append(HexRows.hexCell(HexFixtures.HugeSource.at(at + i))).append(' ');
    }
    HexPattern pattern = HexPattern.hex(text.toString());
    assertEquals(at, find(source, pattern, at - 3 * WINDOW, true, false));
    assertEquals(at, find(source, pattern, at + 50, false, false));
  }
}
