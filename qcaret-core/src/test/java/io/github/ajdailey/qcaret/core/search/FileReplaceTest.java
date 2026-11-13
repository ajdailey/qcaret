package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.document.Replacement;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileReplace} on files searched as a directory replace searches them: line endings,
 * encodings and BOMs kept, every skip leaving the file and its backups untouched, a file larger
 * than every read block, and restoring a run.
 */
class FileReplaceTest {
  private static final CancellationToken NONE = new CancellationToken();
  private static final long MAX = 64L << 20;

  @TempDir Path temp;

  /** A file searched for a query, ready to replace every match. */
  private FileReplace.Target searched(Path file, String query, boolean regex, TextEncoding encoding)
      throws IOException {
    byte[] bytes = Files.readAllBytes(file);
    int bom = encoding.bomBytes().length;
    String text = new String(bytes, bom, bytes.length - bom, encoding.charset());
    TextSearch.Outcome outcome =
        TextSearch.search(matcher(query, regex), text, null, 1_000_000, 1_000_000, NONE);
    int[] spans = new int[outcome.hits().size() * 2];
    for (int i = 0; i < outcome.hits().size(); i++) {
      spans[2 * i] = outcome.hits().get(i).start();
      spans[2 * i + 1] = outcome.hits().get(i).end();
    }
    return new FileReplace.Target(file, FileSearch.digest(bytes), encoding, spans);
  }

  private static TextMatcher matcher(String query, boolean regex) {
    return new SearchQuery(query, regex, true, false).compile();
  }

  private ReplaceBackups backups() throws IOException {
    return ReplaceBackups.start(temp.resolve("settings").resolve("backups"), Instant.now());
  }

  private FileReplace.Outcome replace(
      FileReplace.Target target, String query, boolean regex, String with, ReplaceBackups backups) {
    TextMatcher m = matcher(query, regex);
    return FileReplace.replace(target, m, m.template(with), backups, MAX, null, NONE);
  }

  private static final TextEncoding UTF8 = new TextEncoding(StandardCharsets.UTF_8, false);

  @Test
  void aReplacementLineBreakTakesTheFileLineEndingAndOtherBreaksStay() throws IOException {
    Path file = Files.writeString(temp.resolve("crlf.txt"), "a key\r\nkey b\r\nc\nkey\r\n");
    FileReplace.Outcome outcome =
        replace(searched(file, "key", false, UTF8), "key", true, "x\\ny", backups());
    assertTrue(outcome.changed());
    assertEquals(3, outcome.replaced());
    assertEquals(
        "a x\r\ny\r\nx\r\ny b\r\nc\nx\r\ny\r\n", Files.readString(file, StandardCharsets.UTF_8));
  }

  @Test
  void utf16WithABomKeepsItsBomAndByteOrder() throws IOException {
    TextEncoding utf16 = new TextEncoding(StandardCharsets.UTF_16LE, true);
    byte[] body = "one key\ntwo key\n".getBytes(StandardCharsets.UTF_16LE);
    byte[] bytes = new byte[body.length + 2];
    bytes[0] = (byte) 0xff;
    bytes[1] = (byte) 0xfe;
    System.arraycopy(body, 0, bytes, 2, body.length);
    Path file = Files.write(temp.resolve("utf16.txt"), bytes);
    assertTrue(
        replace(searched(file, "key", false, utf16), "key", false, "lock", backups()).changed());
    byte[] written = Files.readAllBytes(file);
    assertEquals((byte) 0xff, written[0]);
    assertEquals((byte) 0xfe, written[1]);
    assertEquals(
        "one lock\ntwo lock\n",
        new String(written, 2, written.length - 2, StandardCharsets.UTF_16LE));
  }

  @Test
  void anUnmappableReplacementSkipsTheFileUntouched() throws IOException {
    TextEncoding latin = new TextEncoding(Charset.forName("ISO-8859-1"), false);
    Path file = Files.write(temp.resolve("latin.txt"), "café key\n".getBytes(latin.charset()));
    byte[] before = Files.readAllBytes(file);
    ReplaceBackups backups = backups();
    FileReplace.Outcome outcome =
        replace(searched(file, "key", false, latin), "key", false, "€", backups);
    assertEquals(FileReplace.Skip.UNMAPPABLE, outcome.skip());
    assertArrayEquals(before, Files.readAllBytes(file));
    assertTrue(ReplaceBackups.entries(backups.directory()).isEmpty());
  }

  @Test
  void aFileChangedAfterTheSearchIsSkipped() throws IOException {
    Path file = Files.writeString(temp.resolve("changed.txt"), "key one\n");
    FileReplace.Target target = searched(file, "key", false, UTF8);
    Files.writeString(file, "key two\n");
    assertEquals(FileReplace.Skip.CHANGED, replace(target, "key", false, "x", backups()).skip());
    assertEquals("key two\n", Files.readString(file));
  }

  @Test
  void aReadOnlyFileIsSkipped() throws IOException {
    Path file = Files.writeString(temp.resolve("locked.txt"), "key\n");
    FileReplace.Target target = searched(file, "key", false, UTF8);
    readOnly(file, true);
    try {
      Assumptions.assumeFalse(Files.isWritable(file), "the user writes read-only files");
      assertEquals(
          FileReplace.Skip.READ_ONLY, replace(target, "key", false, "x", backups()).skip());
      assertEquals("key\n", Files.readString(file));
    } finally {
      readOnly(file, false);
    }
  }

  private static void readOnly(Path file, boolean on) throws IOException {
    if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
      Files.setPosixFilePermissions(
          file, PosixFilePermissions.fromString(on ? "r--r--r--" : "rw-r--r--"));
    } else {
      Files.setAttribute(file, "dos:readonly", on);
    }
  }

  @Test
  void aMatchTheQueryDoesNotMatchAgainIsDropped() throws IOException {
    Path file = Files.writeString(temp.resolve("drop.txt"), "key key\n");
    FileReplace.Target target = searched(file, "key", false, UTF8);
    int[] spans = {target.spans()[0], target.spans()[1], 5, 7};
    FileReplace.Outcome outcome =
        replace(
            new FileReplace.Target(file, target.digest(), UTF8, spans),
            "key",
            false,
            "x",
            backups());
    assertEquals(1, outcome.replaced());
    assertEquals(1, outcome.dropped());
    assertEquals("x key\n", Files.readString(file));
  }

  @Test
  void malformedAndBinaryFilesAreSkipped() throws IOException {
    Path bad = Files.write(temp.resolve("bad.txt"), new byte[] {'k', 'e', 'y', (byte) 0xc3});
    byte[] badBytes = Files.readAllBytes(bad);
    FileReplace.Target target =
        new FileReplace.Target(bad, FileSearch.digest(badBytes), UTF8, new int[] {0, 3});
    assertEquals(FileReplace.Skip.MALFORMED, replace(target, "key", false, "x", backups()).skip());
    Path binary = Files.write(temp.resolve("data.bin"), new byte[] {'k', 'e', 'y', 0, 1});
    FileReplace.Target data =
        new FileReplace.Target(
            binary, FileSearch.digest(Files.readAllBytes(binary)), UTF8, new int[] {0, 3});
    assertEquals(FileReplace.Skip.BINARY, replace(data, "key", false, "x", backups()).skip());
  }

  @Test
  void aFileLargerThanEveryBlockIsReplacedAtBothEnds() throws IOException {
    StringBuilder text = new StringBuilder("key first\n");
    while (text.length() < 3 << 20) {
      text.append("filler line of plain text without the word\n");
    }
    text.append("key last\n");
    Path file = Files.writeString(temp.resolve("large.txt"), text);
    FileReplace.Target target = searched(file, "key", false, UTF8);
    MemoryBudget budget = new MemoryBudget(1L << 30, 60);
    TextMatcher m = matcher("key", false);
    FileReplace.Outcome outcome =
        FileReplace.replace(target, m, m.template("lock"), backups(), MAX, budget, NONE);
    assertEquals(2, outcome.replaced());
    String written = Files.readString(file);
    assertEquals(text.length() + 2, written.length());
    assertTrue(written.startsWith("lock first\n"));
    assertTrue(written.endsWith("lock last\n"));
  }

  @Test
  void aCanceledReplaceLeavesTheFileWithoutABackup() throws IOException {
    Path file = Files.writeString(temp.resolve("cancel.txt"), "key\n");
    final FileReplace.Target target = searched(file, "key", false, UTF8);
    final ReplaceBackups backups = backups();
    final CancellationToken token = new CancellationToken();
    token.cancel();
    final TextMatcher m = matcher("key", false);
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() {
            FileReplace.replace(target, m, m.template("x"), backups, MAX, null, token);
          }
        });
    assertEquals("key\n", Files.readString(file));
    assertTrue(ReplaceBackups.entries(backups.directory()).isEmpty());
  }

  /** The header sits in the first block; the body runs past every block. */
  @Test
  void aFileLargerThanEveryBlockRestoresByteForByte() throws IOException {
    String original = "key\n" + "0123456789 mock text\n".repeat(200_000);
    Path file = Files.writeString(temp.resolve("large.txt"), original);
    ReplaceBackups backups = backups();
    assertTrue(replace(searched(file, "key", false, UTF8), "key", false, "x", backups).changed());
    ReplaceBackups.Entry entry = ReplaceBackups.entries(backups.directory()).get(0);
    assertEquals(
        ReplaceBackups.Restore.RESTORED,
        ReplaceBackups.restore(backups.directory(), entry, NONE).result());
    assertEquals(original, Files.readString(file));
  }

  @Test
  void aDamagedBackupFailsAndLeavesTheFile() throws IOException {
    Path file = Files.writeString(temp.resolve("one.txt"), "key 1\n");
    ReplaceBackups backups = backups();
    assertTrue(replace(searched(file, "key", false, UTF8), "key", false, "x", backups).changed());
    ReplaceBackups.Entry entry = ReplaceBackups.entries(backups.directory()).get(0);
    Files.write(
        backups.directory().resolve(entry.number() + ".bak"),
        "damaged\n".getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.APPEND);
    assertEquals(
        ReplaceBackups.Restore.FAILED,
        ReplaceBackups.restore(backups.directory(), entry, NONE).result());
    assertEquals("x 1\n", Files.readString(file));
  }

  @Test
  void aRunRestoresItsFilesAndRefusesOneChangedSince() throws IOException {
    Path one = Files.writeString(temp.resolve("one.txt"), "key 1\n");
    Path two = Files.writeString(temp.resolve("two.txt"), "key 2\n");
    ReplaceBackups backups = backups();
    assertTrue(replace(searched(one, "key", false, UTF8), "key", false, "x", backups).changed());
    assertTrue(replace(searched(two, "key", false, UTF8), "key", false, "x", backups).changed());
    Files.writeString(two, "edited later\n");
    List<ReplaceBackups.Entry> entries = ReplaceBackups.entries(backups.directory());
    assertEquals(2, entries.size());
    assertEquals(
        ReplaceBackups.Restore.RESTORED,
        ReplaceBackups.restore(backups.directory(), entries.get(0), NONE).result());
    assertEquals(
        ReplaceBackups.Restore.CHANGED_SINCE,
        ReplaceBackups.restore(backups.directory(), entries.get(1), NONE).result());
    assertEquals(
        ReplaceBackups.Restore.UNCHANGED,
        ReplaceBackups.restore(backups.directory(), entries.get(0), NONE).result());
    assertEquals("key 1\n", Files.readString(one));
    assertEquals("edited later\n", Files.readString(two));
  }

  @Test
  void runsAreListedNewestFirstAndPrunedAfterTheirDays() throws IOException {
    Path root = temp.resolve("backups");
    Instant now = Instant.parse("2026-11-05T10:00:00Z");
    ReplaceBackups old = ReplaceBackups.start(root, now.minusSeconds(8 * 86_400L));
    ReplaceBackups recent = ReplaceBackups.start(root, now.minusSeconds(60));
    ReplaceBackups same = ReplaceBackups.start(root, now.minusSeconds(60));
    assertEquals(recent.id() + "-2", same.id());
    Files.createDirectory(root.resolve("not-a-run"));
    List<ReplaceBackups.Run> runs = ReplaceBackups.list(root);
    assertEquals(3, runs.size());
    assertEquals(same.id(), runs.get(0).id());
    assertEquals(old.id(), runs.get(2).id());
    assertEquals(1, ReplaceBackups.prune(root, ReplaceBackups.DEFAULT_DAYS, now));
    assertEquals(2, ReplaceBackups.list(root).size());
    assertTrue(Files.isDirectory(root.resolve("not-a-run")));
  }

  @Test
  void aDecodeOnlyCharsetEncodesNothing() {
    TextEncoding auto = TextEncoding.of(Charset.forName("x-JISAutoDetect"), false);
    assertFalse(FileReplace.encodable(List.of(new Replacement(0, 1, "mock")), auto));
    assertFalse(FileReplace.holds("mock", auto, new byte[32]));
  }

  @Test
  void aManifestPathKeepsTabsAndBackslashes() {
    String path = "dir\\with\ttab\nand\rbreaks";
    assertEquals(path, ReplaceBackups.unescape(ReplaceBackups.escape(path)));
    assertNull(ReplaceBackups.started(Path.of("20261105-1000")));
  }
}
