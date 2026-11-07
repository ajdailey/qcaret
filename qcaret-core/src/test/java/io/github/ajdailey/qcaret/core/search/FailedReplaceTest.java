package io.github.ajdailey.qcaret.core.search;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes that fail partway, as on a full disk. The {@code ulimit} cases run the write in a JVM
 * under {@code ulimit -f}, so a write past 64 KiB fails with EFBIG; the other cases call {@link
 * ReplaceBackups} directly.
 */
class FailedReplaceTest {
  private static final TextEncoding UTF8 = new TextEncoding(StandardCharsets.UTF_8, false);
  private static final long MAX = 64L << 20;

  @TempDir Path temp;

  private static FileReplace.Target target(Path file, byte[] bytes, TextMatcher matcher) {
    String text = new String(bytes, StandardCharsets.UTF_8);
    TextSearch.Outcome outcome =
        TextSearch.search(matcher, text, null, 1_000_000, 1_000_000, new CancellationToken());
    int[] spans = new int[outcome.hits().size() * 2];
    for (int i = 0; i < outcome.hits().size(); i++) {
      spans[2 * i] = outcome.hits().get(i).start();
      spans[2 * i + 1] = outcome.hits().get(i).end();
    }
    return new FileReplace.Target(file, FileSearch.digest(bytes), UTF8, spans);
  }

  private static FileReplace.Outcome replace(Path file, Path backups, String with)
      throws Exception {
    TextMatcher matcher = new SearchQuery("KEY", false, true, false).compile();
    return FileReplace.replace(
        target(file, Files.readAllBytes(file), matcher),
        matcher,
        matcher.template(with),
        ReplaceBackups.start(backups, Instant.now()),
        MAX,
        null,
        new CancellationToken());
  }

  /** Replaces in args[1] with backups under args[2], or restores args[2]'s newest run. */
  public static void main(String[] args) throws Exception {
    if (args[0].equals("replace")) {
      FileReplace.Outcome result = replace(Path.of(args[1]), Path.of(args[2]), "y".repeat(4096));
      System.out.println("changed=" + result.changed() + " error=" + result.error());
    } else {
      System.out.println("restored=" + restoreNewest(Path.of(args[2])).result());
    }
  }

  private static ReplaceBackups.Restored restoreNewest(Path backups) throws Exception {
    Path run = ReplaceBackups.list(backups).get(0).directory();
    ReplaceBackups.Entry entry = ReplaceBackups.entries(run).get(0);
    return ReplaceBackups.restore(run, entry, new CancellationToken());
  }

  private static String limited(String mode, Path file, Path backups) throws Exception {
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String classPath =
        location(FailedReplaceTest.class) + File.pathSeparator + location(FileReplace.class);
    String command =
        String.join(
            " ",
            "ulimit -f 64; exec",
            "'" + javaBin + "'",
            "-XX:-UsePerfData -cp",
            "'" + classPath + "'",
            FailedReplaceTest.class.getName(),
            mode,
            "'" + file + "'",
            "'" + backups + "'");
    Process process =
        new ProcessBuilder(List.of("bash", "-c", command)).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output;
  }

  private static Path location(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  private void assumeUnix() {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
  }

  @Test
  void aFailedInPlaceReplaceKeepsTheOriginalRestorable() throws Exception {
    assumeUnix();
    Path file = temp.resolve("a.txt");
    byte[] original = "KEY line\n".repeat(1000).getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    Files.createLink(temp.resolve("link.txt"), file);
    Path backups = temp.resolve("backups");
    String output = limited("replace", file, backups);
    assertTrue(output.contains("changed=false"), output);
    assertNotEquals(original.length, Files.size(file), "the write stopped partway");
    assertEquals(1, ReplaceBackups.list(backups).get(0).files());
    assertEquals(ReplaceBackups.Restore.RESTORED, restoreNewest(backups).result());
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  @Test
  void aFailedAtomicReplaceDropsItsBackup() throws Exception {
    assumeUnix();
    Path file = temp.resolve("a.txt");
    byte[] original = "KEY line\n".repeat(1000).getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    Path backups = temp.resolve("backups");
    String output = limited("replace", file, backups);
    assertTrue(output.contains("changed=false"), output);
    assertArrayEquals(original, Files.readAllBytes(file));
    assertEquals(0, ReplaceBackups.list(backups).get(0).files());
  }

  @Test
  void aRestoreThatFailsPartwayCanBeRetried() throws Exception {
    assumeUnix();
    Path file = temp.resolve("a.txt");
    byte[] original = "KEY line\n".repeat(10_000).getBytes(StandardCharsets.UTF_8);
    Files.write(file, original);
    Files.createLink(temp.resolve("link.txt"), file);
    Path backups = temp.resolve("backups");
    assertTrue(replace(file, backups, "K").changed());
    byte[] replaced = Files.readAllBytes(file);
    String output = limited("restore", file, backups);
    assertTrue(output.contains("restored=FAILED"), output);
    byte[] partial = Files.readAllBytes(file);
    assertFalse(Arrays.equals(original, partial), "the restore stopped partway");
    assertFalse(Arrays.equals(replaced, partial), "the restore stopped partway");
    assertEquals(ReplaceBackups.Restore.RESTORED, restoreNewest(backups).result());
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  private int record(ReplaceBackups backups, Path file, byte[] original) throws Exception {
    return backups.record(file, original, FileSearch.digest(new byte[] {'n'}));
  }

  @Test
  void aFileThatKeptItsBytesDropsTheBackup() throws Exception {
    Path file = temp.resolve("a.txt");
    byte[] original = {'o'};
    Files.write(file, original);
    ReplaceBackups backups = ReplaceBackups.start(temp.resolve("backups"), Instant.now());
    int entry = record(backups, file, original);
    backups.writeFailed(entry, file, FileSearch.digest(original));
    assertEquals(List.of(), ReplaceBackups.entries(backups.directory()));
  }

  @Test
  void aFileLeftWithOtherBytesRestores() throws Exception {
    Path file = temp.resolve("a.txt");
    byte[] original = {'o'};
    Files.write(file, original);
    ReplaceBackups backups = ReplaceBackups.start(temp.resolve("backups"), Instant.now());
    int entry = record(backups, file, original);
    Files.write(file, new byte[] {'p', 'a', 'r', 't'});
    backups.writeFailed(entry, file, FileSearch.digest(original));
    assertEquals(
        ReplaceBackups.Restore.RESTORED,
        ReplaceBackups.restore(
                backups.directory(),
                ReplaceBackups.entries(backups.directory()).get(0),
                new CancellationToken())
            .result());
    assertArrayEquals(original, Files.readAllBytes(file));
  }

  @Test
  void aFileThatCannotBeReadKeepsTheBackup() throws Exception {
    Path file = temp.resolve("a.txt");
    byte[] original = {'o'};
    ReplaceBackups backups = ReplaceBackups.start(temp.resolve("backups"), Instant.now());
    int entry = record(backups, file, original);
    backups.writeFailed(entry, file, FileSearch.digest(original));
    assertEquals(1, ReplaceBackups.entries(backups.directory()).size());
  }
}
