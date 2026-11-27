package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.hex.ByteOffsets;
import io.github.ajdailey.qcaret.core.hex.EncodedHexSource;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * {@link HexText}'s rebuild after edits, which reuses the offsets before the first changed char,
 * equals a whole build of the same text: the same size, bytes and char-to-byte offsets. Random
 * edits of a text of several 64 Ki-char blocks with multi-byte chars and surrogate pairs, some
 * edits batched before one rebuild, some splitting a pair, in UTF-8 and GB18030.
 */
class HexTextRebuildTest {
  private static final Duration BOUND = Duration.ofSeconds(30);
  private static final String[] PIECES = {"a", "z", "\n", "é", "€", "😀", "中", "\r\n", "\t"};
  private static final int EDITS = 30;

  @Test
  void utf8RebuildsEqualAWholeBuild() throws Exception {
    check("UTF-8", 20261126L);
  }

  @Test
  void gb18030RebuildsEqualAWholeBuild() throws Exception {
    check("GB18030", 20261127L);
  }

  private void check(String charset, long seed) throws Exception {
    final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
    ManualUiDispatcher ui = new ManualUiDispatcher();
    Random random = new Random(seed);
    TextEncoding encoding = TextEncoding.of(Charset.forName(charset), false);
    Document document =
        Document.of(text(random, 200_000), encoding, LineEnding.LF, UndoLimits.DEFAULTS);
    try (Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            })) {
      Built target = new Built();
      HexText text = new HexText(document, workers, ui, target);
      assertTrue(text.start() || awaitBuilt(ui, text, document));
      for (int i = 0; i < EDITS; i++) {
        int batch = 1 + random.nextInt(3);
        for (int j = 0; j < batch; j++) {
          edit(random, document);
        }
        assertTrue(awaitBuilt(ui, text, document), "rebuild " + i);
        compare(text.current(), document, encoding);
      }
      text.stop();
    }
    assertEquals(List.of(), failures);
  }

  private static boolean awaitBuilt(ManualUiDispatcher ui, final HexText text, Document document)
      throws InterruptedException {
    return ui.runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return text.current() != null;
          }
        },
        BOUND);
  }

  private static void edit(Random random, Document document) {
    int length = document.text().length();
    int offset = random.nextInt(length + 1);
    int remove = random.nextInt(4) == 0 ? 0 : random.nextInt(Math.min(40, length - offset) + 1);
    String insert = random.nextInt(4) == 0 ? "" : text(random, 1 + random.nextInt(30));
    if (remove == 0 && insert.isEmpty()) {
      insert = "x";
    }
    document.apply(EditBatch.replace(offset, remove, insert));
  }

  private static String text(Random random, int chars) {
    StringBuilder out = new StringBuilder(chars + 2);
    while (out.length() < chars) {
      out.append(PIECES[random.nextInt(PIECES.length)]);
    }
    return out.toString();
  }

  /** Checks a rebuild against a whole build of the same snapshot. */
  private static void compare(ByteOffsets rebuilt, Document document, TextEncoding encoding) {
    ByteOffsets whole = ByteOffsets.build(document.text(), encoding, new CancellationToken());
    assertEquals(whole.size(), rebuilt.size());
    int length = document.text().length();
    for (int at = 0; at <= length; at += 4999) {
      assertEquals(whole.byteOffset(at), rebuilt.byteOffset(at), "char " + at);
    }
    assertEquals(whole.byteOffset(length), rebuilt.byteOffset(length));
    EncodedHexSource expected = new EncodedHexSource(whole);
    EncodedHexSource actual = new EncodedHexSource(rebuilt);
    int size = (int) whole.size();
    byte[] want = new byte[size];
    byte[] got = new byte[size];
    expected.read(0, want, size, new BitSet());
    actual.read(0, got, size, new BitSet());
    assertArrayEquals(want, got);
  }

  /** A target that records nothing; {@link HexText#current} is the result. */
  private static final class Built implements HexText.Target {
    @Override
    public void built(EncodedHexSource source) {
      // Read through HexText.current.
    }

    @Override
    public void stale() {
      // Not checked.
    }

    @Override
    public void unsupported() {
      throw new AssertionError("unsupported");
    }

    @Override
    public ActivityIndicator busy() {
      return new ActivityIndicator() {
        @Override
        public void setActive() {
          // No cursor.
        }

        @Override
        public void setIdle() {
          // No cursor.
        }
      };
    }
  }
}
