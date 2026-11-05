package io.github.ajdailey.qcaret.core.watch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.io.FileBaseline;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileCheck} on the changes another program makes. Each case that depends on the digest
 * rather than the stamp sets the modification time back to the baseline's, as a file system with a
 * coarse clock, a copy that keeps times, or NTFS tunneling would (QLogg TEST-17).
 */
class FileCheckTest {
  private static final CancellationToken TOKEN = new CancellationToken();

  @TempDir Path temp;

  private Path write(String name, String text) throws IOException {
    return Files.writeString(temp.resolve(name), text, StandardCharsets.UTF_8);
  }

  /** A baseline taken long after the file's modification, so its stamp is trusted. */
  private static FileBaseline settled(Path file) throws IOException {
    FileBaseline read = FileCheck.read(file, TOKEN);
    return new FileBaseline(
        read.stamp(), read.digest(), read.stamp().modified().toMillis() + 60_000);
  }

  /** A baseline taken at the moment of the file's modification. */
  private static FileBaseline racy(Path file) throws IOException {
    FileBaseline read = FileCheck.read(file, TOKEN);
    return new FileBaseline(read.stamp(), read.digest(), read.stamp().modified().toMillis());
  }

  @Test
  void anUntouchedFileIsUnchangedWithoutADigest() throws Exception {
    Path file = write("a.txt", "one\n");
    FileBaseline baseline = settled(file);
    FileCheck.Result result = FileCheck.check(file, baseline, TOKEN);
    assertEquals(FileCheck.State.UNCHANGED, result.state());
    assertSame(baseline, result.current());
  }

  @Test
  void aTouchWithTheSameBytesIsUnchangedUnderTheNewStamp() throws Exception {
    Path file = write("a.txt", "one\n");
    FileBaseline baseline = settled(file);
    FileTime later = FileTime.fromMillis(baseline.stamp().modified().toMillis() + 5000);
    Files.setLastModifiedTime(file, later);
    FileCheck.Result result = FileCheck.check(file, baseline, TOKEN);
    assertEquals(FileCheck.State.UNCHANGED, result.state());
    assertEquals(later, result.current().stamp().modified());
    assertEquals(baseline.digest(), result.current().digest());
  }

  @Test
  void otherBytesAreChanged() throws Exception {
    Path file = write("a.txt", "one\n");
    FileBaseline baseline = settled(file);
    write("a.txt", "one\ntwo\n");
    FileCheck.Result result = FileCheck.check(file, baseline, TOKEN);
    assertEquals(FileCheck.State.CHANGED, result.state());
    assertEquals(Digests.of(Files.readAllBytes(file), 8), result.current().digest());
  }

  @Test
  void aTruncateAndRegrowToTheSameSizeAndTimeIsChanged() throws Exception {
    Path file = write("a.txt", "abcd\n");
    FileBaseline baseline = racy(file);
    FileTime time = baseline.stamp().modified();
    write("a.txt", "");
    write("a.txt", "wxyz\n");
    Files.setLastModifiedTime(file, time);
    assertEquals(baseline.stamp(), FileStamp.read(file));
    assertEquals(FileCheck.State.CHANGED, FileCheck.check(file, baseline, TOKEN).state());
  }

  @Test
  void aRacyBaselineSettlesAfterOneCheck() throws Exception {
    Path file = write("a.txt", "abcd\n");
    FileBaseline baseline = racy(file);
    FileCheck.Result result = FileCheck.check(file, baseline, TOKEN);
    assertEquals(FileCheck.State.UNCHANGED, result.state());
    assertEquals(baseline.digest(), result.current().digest());
  }

  @Test
  void aFileRenamedOverTheNameIsChanged() throws Exception {
    Path file = write("a.txt", "abcd\n");
    FileBaseline baseline = settled(file);
    Path other = write("b.tmp", "wxyz\n");
    Files.setLastModifiedTime(other, baseline.stamp().modified());
    Files.move(other, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    Assumptions.assumeTrue(
        baseline.stamp().fileKey() != null,
        "without a file key, same size and time read as unchanged until the next racy check");
    assertEquals(FileCheck.State.CHANGED, FileCheck.check(file, baseline, TOKEN).state());
  }

  @Test
  void aFileRenamedOverWithTheSameBytesIsUnchanged() throws Exception {
    Path file = write("a.txt", "abcd\n");
    FileBaseline baseline = settled(file);
    Path other = write("b.tmp", "abcd\n");
    Files.move(other, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    assertEquals(FileCheck.State.UNCHANGED, FileCheck.check(file, baseline, TOKEN).state());
  }

  @Test
  void aDeletedFileIsMissingAndItsReturnIsCompared() throws Exception {
    Path file = write("a.txt", "abcd\n");
    FileBaseline baseline = settled(file);
    Files.delete(file);
    FileCheck.Result gone = FileCheck.check(file, baseline, TOKEN);
    assertEquals(FileCheck.State.MISSING, gone.state());
    assertNull(gone.current());
    write("a.txt", "abcd\n");
    assertEquals(FileCheck.State.UNCHANGED, FileCheck.check(file, baseline, TOKEN).state());
    write("a.txt", "other\n");
    assertEquals(FileCheck.State.CHANGED, FileCheck.check(file, baseline, TOKEN).state());
  }

  @Test
  void aFileWhereNoneWasIsChangedAndNoFileIsUnchanged() throws Exception {
    Path file = temp.resolve("new.txt");
    assertEquals(FileCheck.State.UNCHANGED, FileCheck.check(file, null, TOKEN).state());
    write("new.txt", "x");
    FileCheck.Result result = FileCheck.check(file, null, TOKEN);
    assertEquals(FileCheck.State.CHANGED, result.state());
    assertNotNull(result.current());
  }

  @Test
  void aDirectoryIsMissing() throws Exception {
    Path file = write("a.txt", "abcd\n");
    FileBaseline baseline = settled(file);
    Files.delete(file);
    Files.createDirectory(file);
    assertEquals(FileCheck.State.MISSING, FileCheck.check(file, baseline, TOKEN).state());
  }

  @Test
  void aRetargetedLinkIsChanged() throws Exception {
    Path a = write("a.txt", "first\n");
    Path b = write("b.txt", "second\n");
    Path link = temp.resolve("link.txt");
    try {
      Files.createSymbolicLink(link, a);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links unavailable: " + e);
    }
    FileBaseline baseline = settled(link);
    assertEquals(FileCheck.State.UNCHANGED, FileCheck.check(link, baseline, TOKEN).state());
    Files.delete(link);
    Files.createSymbolicLink(link, b);
    FileCheck.Result result = FileCheck.check(link, baseline, TOKEN);
    assertEquals(FileCheck.State.CHANGED, result.state());
    assertEquals(Digests.of(Files.readAllBytes(b), 7), result.current().digest());
  }

  @Test
  void aFileLargerThanTheDigestBlockIsDigestedWhole() throws Exception {
    byte[] bytes = new byte[(3 << 20) + 17];
    Arrays.fill(bytes, (byte) 'a');
    Path file = Files.write(temp.resolve("large.txt"), bytes);
    FileBaseline baseline = racy(file);
    assertEquals(Digests.of(bytes, bytes.length), baseline.digest());
    bytes[bytes.length - 1] = 'b';
    Files.write(file, bytes);
    Files.setLastModifiedTime(file, baseline.stamp().modified());
    assertEquals(FileCheck.State.CHANGED, FileCheck.check(file, baseline, TOKEN).state());
  }
}
