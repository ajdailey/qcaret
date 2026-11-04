package io.github.ajdailey.qcaret.core.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class UtilTest {
  @Test
  void checksNameTheArgument() {
    Checks.atLeast(1, 1, "count");
    Checks.notNegative(0, "bytes");
    IllegalArgumentException low =
        assertThrows(
            IllegalArgumentException.class,
            new Executable() {
              @Override
              public void execute() {
                Checks.atLeast(0, 1, "count");
              }
            });
    assertEquals("count must be at least 1", low.getMessage());
    IllegalArgumentException negative =
        assertThrows(
            IllegalArgumentException.class,
            new Executable() {
              @Override
              public void execute() {
                Checks.notNegative(-1, "bytes");
              }
            });
    assertEquals("bytes must not be negative", negative.getMessage());
  }

  @Test
  void readLimitedAcceptsTheLimitAndRefusesMore() throws IOException {
    byte[] data = new byte[20000];
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte) i;
    }
    assertArrayEquals(data, Streams.readLimited(new ByteArrayInputStream(data), data.length));
    assertArrayEquals(new byte[0], Streams.readLimited(new ByteArrayInputStream(new byte[0]), 0));
    final ByteArrayInputStream longer = new ByteArrayInputStream(data);
    IOException refused =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                Streams.readLimited(longer, data.length - 1);
              }
            });
    assertEquals("More than 19999 bytes", refused.getMessage());
  }
}
