package io.github.ajdailey.qcaret.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class BoundedCharsTest {
  @Test
  void readsPastTheLimitThrowAndResetStartsOver() {
    final BoundedChars chars = new BoundedChars().reset("abc", 2);
    assertEquals('a', chars.charAt(0));
    assertEquals('b', chars.charAt(1));
    assertThrows(
        BoundedChars.LimitReached.class,
        new Executable() {
          @Override
          public void execute() {
            chars.charAt(2);
          }
        });
    assertEquals(2, chars.used());
    chars.reset("xyz", 10);
    assertEquals(0, chars.used());
    assertEquals('z', chars.charAt(2));
    assertEquals(3, chars.length());
    assertEquals("yz", chars.subSequence(1, 3).toString());
    chars.allow(1);
    chars.charAt(0);
    assertEquals(2, chars.used());
  }

  @Test
  void aBacktrackingRegexEndsAtTheLineLimit() {
    String line = "a".repeat(32) + "!";
    final BoundedChars chars = new BoundedChars().reset(line, BoundedChars.lineLimit(line));
    assertEquals(8L * 33 + BoundedChars.BASE_READS, BoundedChars.lineLimit(line));
    long started = System.nanoTime();
    assertThrows(
        BoundedChars.LimitReached.class,
        new Executable() {
          @Override
          public void execute() {
            Pattern.compile("(.*a){12}$").matcher(chars).find();
          }
        });
    assertTrue(System.nanoTime() - started < 5_000_000_000L);
    assertEquals(BoundedChars.lineLimit(line), chars.used());
  }

  @Test
  void aCanceledTokenStopsTheReadsWithinTheCheckInterval() {
    CancellationToken token = new CancellationToken();
    final BoundedChars chars = new BoundedChars().reset("abc", Long.MAX_VALUE, token);
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            for (int i = 0; i <= BoundedChars.CHECK_READS; i++) {
              chars.charAt(i % 3);
            }
          }
        });
    assertEquals(BoundedChars.CHECK_READS - 1, chars.used());
  }
}
