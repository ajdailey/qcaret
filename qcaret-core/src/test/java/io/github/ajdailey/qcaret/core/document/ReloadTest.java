package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.buffer.TextCursor;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The two-pass comparison of a file with an open text, against a comparison of two whole texts. */
class ReloadTest {
  private static final int BLOCK = 1 << 20;
  private static final LoadOptions UTF8 = LoadOptions.DEFAULTS.withForced(StandardCharsets.UTF_8);
  private static final Runnable NOTHING =
      new Runnable() {
        @Override
        public void run() {
          // Nothing between the passes.
        }
      };

  @TempDir Path temp;

  /** The span between the common prefix and suffix of two whole texts. */
  private static Replacement whole(TextContent before, TextContent after) {
    int b = before.length();
    int a = after.length();
    int limit = Math.min(a, b);
    int prefix = 0;
    TextCursor x = before.cursor(0);
    TextCursor y = after.cursor(0);
    while (prefix < limit && x.next() == y.next()) {
      prefix++;
    }
    if (prefix == a && prefix == b) {
      return null;
    }
    int suffix = 0;
    x = before.cursor(b);
    y = after.cursor(a);
    while (suffix < limit - prefix && x.previous() == y.previous()) {
      suffix++;
    }
    return new Replacement(
        prefix, b - prefix - suffix, after.subSequence(prefix, a - suffix).toString());
  }

  private static Document document(String text) {
    return Document.of(
        text, TextEncoding.of(StandardCharsets.UTF_8, false), LineEnding.LF, UndoLimits.DEFAULTS);
  }

  private Path write(String text) throws IOException {
    return Files.writeString(temp.resolve("file.txt"), text, StandardCharsets.UTF_8);
  }

  private static ReloadedFile reload(Path file, TextContent current, int block, int chars)
      throws IOException {
    return DocumentFiles.reload(
        file,
        UTF8,
        current,
        LoadReservation.UNMETERED,
        new CancellationToken(),
        block,
        chars,
        NOTHING);
  }

  private void assertSameAsWhole(String before, String after, int block, int chars)
      throws IOException {
    Path file = write(after);
    Document current = document(before);
    Replacement expected = whole(current.text(), document(after).text());
    assertEquals(expected, reload(file, current.text(), block, chars).replacement());
  }

  private static String text(Random random, int length) {
    String alphabet = "aab\r\n é中😀";
    StringBuilder out = new StringBuilder();
    while (out.length() < length) {
      out.appendCodePoint(alphabet.codePointAt(alphabet.offsetByCodePoints(0, random.nextInt(9))));
    }
    return out.toString();
  }

  @Test
  void theSpanIsTheWholeTextComparisonsOnEditedTexts() throws Exception {
    Random random = new Random(11);
    for (int i = 0; i < 400; i++) {
      String before = text(random, random.nextInt(60));
      int points = before.codePointCount(0, before.length());
      int first = random.nextInt(points + 1);
      int last = first + random.nextInt(points - first + 1);
      int from = before.offsetByCodePoints(0, first);
      int to = before.offsetByCodePoints(0, last);
      String edited = before.substring(0, from) + text(random, random.nextInt(12));
      String after = edited + before.substring(to);
      assertSameAsWhole(before, after, 1 + random.nextInt(16), 8 + random.nextInt(16));
    }
  }

  @Test
  void repeatedTextKeepsThePrefixAndSuffixApart() throws Exception {
    assertSameAsWhole("aaaa", "aa", 4, 8);
    assertSameAsWhole("aa", "aaaa", 4, 8);
    assertSameAsWhole("abab", "ab", 4, 8);
    assertSameAsWhole("", "new", 4, 8);
    assertSameAsWhole("old", "", 4, 8);
    assertSameAsWhole("abXcdXef", "abYcdYef", 2, 8);
  }

  @Test
  void equalTextsGiveNoReplacementAndTheFilesFacts() throws Exception {
    Path file = write("one\r\ntwo\r\n");
    ReloadedFile read =
        DocumentFiles.reload(
            file,
            UTF8,
            document("one\r\ntwo\r\n").text(),
            LoadReservation.UNMETERED,
            new CancellationToken());
    LoadedFile loaded = DocumentFiles.load(file, UTF8, new CancellationToken());
    assertNull(read.replacement());
    assertEquals(LineEnding.CRLF, read.lineEnding());
    assertEquals(loaded.document().encoding(), read.encoding());
    assertEquals(loaded.baseline().digest(), read.baseline().digest());
  }

  @Test
  void aByteOrderMarkIsNotText() throws Exception {
    Path file = temp.resolve("bom.txt");
    Files.write(file, new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', 'b', 'c'});
    ReloadedFile read =
        DocumentFiles.reload(
            file,
            LoadOptions.DEFAULTS,
            document("abd").text(),
            LoadReservation.UNMETERED,
            new CancellationToken());
    assertEquals(new Replacement(2, 1, "c"), read.replacement());
    assertTrue(read.encoding().bomBytes().length > 0);
  }

  /** Edits at both ends and past every block, applied as one undo step. */
  @Test
  void aFileLargerThanEveryBlockReloadsAsOneStep() throws Exception {
    StringBuilder text = new StringBuilder();
    while (text.length() < 3 * BLOCK) {
      text.append("0123456789 mock é text 中\n");
    }
    String before = text.toString();
    String after =
        "start " + before.substring(40, 2 * BLOCK) + "middle" + before.substring(2 * BLOCK);
    Path file = write(after);
    Document current = document(before);
    ReloadedFile read =
        DocumentFiles.reload(
            file, UTF8, current.text(), LoadReservation.UNMETERED, new CancellationToken());
    assertEquals(whole(current.text(), document(after).text()), read.replacement());
    current.apply(EditBatch.of(EditBatch.Kind.COMMAND, List.of(read.replacement())));
    assertEquals(after, current.text().toString());
    assertEquals(1, current.undoSteps());
    current.undo(null);
    assertEquals(before, current.text().toString());
  }

  @Test
  void aFileWrittenBetweenThePassesFailsTheRead() throws Exception {
    final Path file = write("one two three");
    final Document current = document("one 2 three");
    IOException e =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                DocumentFiles.reload(
                    file,
                    UTF8,
                    current.text(),
                    LoadReservation.UNMETERED,
                    new CancellationToken(),
                    4,
                    8,
                    new Runnable() {
                      @Override
                      public void run() {
                        try {
                          Files.writeString(file, "one TWO three");
                        } catch (IOException failure) {
                          throw new IllegalStateException(failure);
                        }
                      }
                    });
              }
            });
    assertEquals("The file changed during the read", e.getMessage());
  }

  @Test
  void aFileThatStopsDecodingBetweenThePassesFailsTheRead() throws Exception {
    final Path file = write("one two");
    final Document current = document("one 2");
    IOException e =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                DocumentFiles.reload(
                    file,
                    UTF8,
                    current.text(),
                    LoadReservation.UNMETERED,
                    new CancellationToken(),
                    4,
                    8,
                    new Runnable() {
                      @Override
                      public void run() {
                        try {
                          Files.write(file, new byte[] {'o', 'n', 'e', ' ', 't', (byte) 0xff, 'o'});
                        } catch (IOException failure) {
                          throw new IllegalStateException(failure);
                        }
                      }
                    });
              }
            });
    assertEquals(IOException.class, e.getClass());
  }

  @Test
  void bytesTheCharsetCannotDecodeFailThePass() throws Exception {
    final Path file = temp.resolve("bad.txt");
    Files.write(file, new byte[] {'a', (byte) 0xff});
    assertThrows(
        TextCodingException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            DocumentFiles.reload(
                file,
                UTF8,
                document("a").text(),
                LoadReservation.UNMETERED,
                new CancellationToken());
          }
        });
  }

  @Test
  void aCancelEndsTheRead() throws Exception {
    final Path file = write("changed");
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            DocumentFiles.reload(
                file, UTF8, document("text").text(), LoadReservation.UNMETERED, token);
          }
        });
  }

  /** The charge is the buffers, the inserted text twice and the removed text once. */
  @Test
  void theSpanIsChargedUntilTheReservationCloses() throws Exception {
    Path file = write("a" + "中".repeat(5000) + "z");
    long buffers = Files.size(file);
    MemoryBudget budget = new MemoryBudget(1L << 30, 100);
    try (LoadReservation reservation = LoadReservation.reload(budget, 0, UndoLimits.DEFAULTS)) {
      DocumentFiles.reload(
          file,
          UTF8,
          document("a" + "b".repeat(3000) + "z").text(),
          reservation,
          new CancellationToken());
      assertEquals(buffers + 2 * 2 * 5000 + 3000, budget.reserved());
    }
    assertEquals(0, budget.reserved());
  }

  @Test
  void aChangeInsideTheHeadroomNeedsNoBudget() throws Exception {
    Path file = write("x".repeat(1000) + "new" + "y".repeat(1000));
    Document current = document("x".repeat(1000) + "old" + "y".repeat(1000));
    MemoryBudget budget = new MemoryBudget(10_000, 100);
    long retained = 2 * current.text().length();
    assertTrue(budget.reserve(budget.limit()));
    try (LoadReservation reservation =
        LoadReservation.reload(budget, 2 * retained, UndoLimits.DEFAULTS)) {
      ReloadedFile read =
          DocumentFiles.reload(file, UTF8, current.text(), reservation, new CancellationToken());
      assertEquals(new Replacement(1000, 3, "new"), read.replacement());
      assertEquals(budget.limit(), budget.reserved());
    }
  }

  @Test
  void aSpanTheBudgetCannotHoldEndsTheRead() throws Exception {
    StringBuilder text = new StringBuilder();
    Random random = new Random(5);
    while (text.length() < 4 * BLOCK) {
      text.append((char) ('a' + random.nextInt(26)));
    }
    final Path file = write(text.toString());
    final Document current = document("b" + text.substring(1).toUpperCase());
    final MemoryBudget budget = new MemoryBudget(8L * BLOCK, 100);
    try (final LoadReservation reservation =
        LoadReservation.reload(budget, 0, UndoLimits.DEFAULTS)) {
      assertNotNull(
          assertThrows(
              LoadTooLargeException.class,
              new Executable() {
                @Override
                public void execute() throws Throwable {
                  DocumentFiles.reload(
                      file, UTF8, current.text(), reservation, new CancellationToken());
                }
              }));
    }
    assertEquals(0, budget.reserved());
  }

  /** Bytes held by {@code add} stay on top of what {@code charge} raises. */
  @Test
  void anAddedHoldStaysBesideTheCharge() throws Exception {
    MemoryBudget budget = new MemoryBudget(1000, 100);
    LoadCost cost = new LoadCost(100, 100, 100, 100);
    try (LoadReservation reservation = LoadReservation.reserve(budget, cost)) {
      reservation.add(50);
      reservation.charge(120, 100);
      assertEquals(170, budget.reserved());
    }
  }
}
