package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parsing and writing {@code sha256sum} lines, and checking entries against files. */
class ChecksumListTest {
  @TempDir Path temp;

  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String ABC_MD5 = "900150983cd24fb0d6963f7d28e17f72";

  private static final LongConsumer NONE =
      new LongConsumer() {
        @Override
        public void accept(long value) {
          // No progress.
        }
      };

  @Test
  void parsesTextAndBinaryLinesWithTheAlgorithmFromTheLength() {
    String text =
        ABC_SHA256
            + "  a.txt\r\n"
            + "\n"
            + ABC_MD5.toUpperCase(Locale.ROOT)
            + " *dir/b c.bin\n"
            + "not a checksum line\n"
            + ABC_MD5
            + "1 x\n"
            + ABC_MD5
            + " -x\n"
            + ABC_MD5
            + "  ";
    ChecksumList list = ChecksumList.parse(text);
    assertEquals(
        List.of(
            new ChecksumList.Entry(1, ChecksumAlgorithm.SHA_256, ABC_SHA256, "a.txt", false),
            new ChecksumList.Entry(3, ChecksumAlgorithm.MD5, ABC_MD5, "dir/b c.bin", true)),
        list.entries());
    assertEquals(4, list.badCount());
    assertEquals(List.of(4, 5, 6, 7), list.badLines());
  }

  @Test
  void piecesParseAsTheWholeText() {
    String text = ABC_MD5 + "  one\n" + ABC_SHA256 + "  two";
    ChecksumList.Parser parser = new ChecksumList.Parser();
    for (int i = 0; i < text.length(); i++) {
      parser.add(text, i, i + 1);
    }
    assertEquals(ChecksumList.parse(text), parser.finish());
    assertEquals(2, parser.finish().entries().size());
  }

  @Test
  void aLineEndsAtLfCrLfOrCr() {
    String text = ABC_MD5 + "  a\r" + ABC_MD5 + "  b\r\n\r" + ABC_MD5 + "  c\n" + ABC_MD5 + "  d\r";
    ChecksumList list = ChecksumList.parse(text);
    assertEquals(
        List.of(
            new ChecksumList.Entry(1, ChecksumAlgorithm.MD5, ABC_MD5, "a", false),
            new ChecksumList.Entry(2, ChecksumAlgorithm.MD5, ABC_MD5, "b", false),
            new ChecksumList.Entry(4, ChecksumAlgorithm.MD5, ABC_MD5, "c", false),
            new ChecksumList.Entry(5, ChecksumAlgorithm.MD5, ABC_MD5, "d", false)),
        list.entries());
    assertEquals(0, list.badCount());
  }

  /** Verify reads a chosen list in 8 KiB pieces, so a CR LF pair can straddle two of them. */
  @Test
  void aCrLfPairSplitBetweenPiecesIsOneBreak() {
    StringBuilder b = new StringBuilder();
    int lines = 0;
    while (b.length() < 5 * 8192) {
      b.append(ABC_SHA256).append("  file").append(lines++).append("\r\n");
    }
    String text = b.toString();
    ChecksumList whole = ChecksumList.parse(text);
    assertEquals(lines, whole.entries().size());
    assertEquals(0, whole.badCount());
    int splits = 0;
    for (int shift = 0; shift < 80; shift++) {
      ChecksumList.Parser parser = new ChecksumList.Parser();
      int at = 0;
      for (int end = 8192 - shift; end < text.length(); end += 8192) {
        if (text.charAt(end - 1) == '\r') {
          splits++;
        }
        parser.add(text, at, end);
        at = end;
      }
      parser.add(text, at, text.length());
      assertEquals(whole, parser.finish());
    }
    assertTrue(splits > 0);
  }

  @Test
  void oneLeadingByteOrderMarkIsSkipped() {
    ChecksumList.Parser parser = new ChecksumList.Parser();
    parser.add("\uFEFF", 0, 1);
    parser.add(ABC_MD5 + "  a\n", 0, ABC_MD5.length() + 4);
    assertEquals("a", parser.finish().entries().get(0).name());
    ChecksumList twice = ChecksumList.parse("\uFEFF\uFEFF" + ABC_MD5 + "  a\n");
    assertEquals(List.of(1), twice.badLines());
  }

  @Test
  void anOverlongLineIsBad() {
    String text = "x".repeat(ChecksumList.MAX_LINE_CHARS + 5) + "\n" + ABC_MD5 + "  a\n";
    ChecksumList list = ChecksumList.parse(text);
    assertEquals(1, list.badCount());
    assertEquals(List.of(1), list.badLines());
    assertEquals("a", list.entries().get(0).name());
  }

  @Test
  void namesWithBackslashesAndBreaksRoundTrip() {
    String name = "a\\b\nc\rd";
    String line = ChecksumList.line(ABC_MD5, name);
    assertEquals("\\" + ABC_MD5 + "  a\\\\b\\nc\\rd", line);
    assertEquals(name, ChecksumList.parse(line).entries().get(0).name());
    assertEquals(ABC_MD5 + "  plain.txt", ChecksumList.line(ABC_MD5, "plain.txt"));
    assertNull(ChecksumList.parseLine("\\" + ABC_MD5 + "  a\\q", 1));
  }

  /** OK, FAILED, MISSING, a directory as unreadable and a {@code *} line, in list order. */
  @Test
  void verifyReportsEachOutcome() throws Exception {
    Files.writeString(temp.resolve("good.txt"), "abc", StandardCharsets.US_ASCII);
    Files.writeString(temp.resolve("bad.txt"), "abd", StandardCharsets.US_ASCII);
    Files.writeString(temp.resolve("star.bin"), "abc", StandardCharsets.US_ASCII);
    Files.createDirectory(temp.resolve("folder"));
    Path elsewhere = temp.resolve("elsewhere");
    Files.createDirectory(elsewhere);
    ChecksumList list =
        ChecksumList.parse(
            ABC_SHA256
                + "  good.txt\n"
                + ABC_SHA256
                + "  bad.txt\n"
                + ABC_SHA256
                + "  gone.txt\n"
                + ABC_SHA256
                + "  folder\n"
                + ABC_MD5
                + " *star.bin\n");
    ChecksumCheck.Status[] expected = {
      ChecksumCheck.Status.OK,
      ChecksumCheck.Status.FAILED,
      ChecksumCheck.Status.MISSING,
      ChecksumCheck.Status.UNREADABLE,
      ChecksumCheck.Status.OK
    };
    for (int i = 0; i < expected.length; i++) {
      ChecksumList.Entry entry = list.entries().get(i);
      ChecksumCheck.Result result =
          ChecksumCheck.verify(
              temp, entry, entry.algorithm().newDigest(), new CancellationToken(), NONE);
      assertEquals(expected[i], result.status(), entry.name());
      assertEquals(expected[i] == ChecksumCheck.Status.UNREADABLE, result.error() != null);
    }
    ChecksumList.Entry good = list.entries().get(0);
    assertEquals(
        ChecksumCheck.Status.MISSING,
        ChecksumCheck.verify(
                elsewhere, good, good.algorithm().newDigest(), new CancellationToken(), NONE)
            .status());
  }
}
