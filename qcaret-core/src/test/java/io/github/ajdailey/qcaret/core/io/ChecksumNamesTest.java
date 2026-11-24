package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;

/** The names a checksum list may give: refused UNC and device names, regular files only. */
class ChecksumNamesTest {
  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String SHARE_REFUSAL = "names a network share the list is not on";
  private static final String DEVICE_REFUSAL = "Path names a Windows device";
  private static final OsTraits WINDOWS = OsTraits.of("Windows 11");
  private static final OsTraits LINUX = OsTraits.of("Linux");

  private static final LongConsumer NONE =
      new LongConsumer() {
        @Override
        public void accept(long value) {
          // No progress.
        }
      };

  @TempDir Path temp;

  private static void refused(
      final String folder, final String name, final OsTraits os, String reason) {
    IOException e =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                ChecksumCheck.checkName(folder, name, os);
              }
            },
            name);
    assertEquals(reason, e.getMessage(), name);
  }

  private static ChecksumList.Entry entry(String name) {
    return ChecksumList.parse(ChecksumList.line(ABC_SHA256, name) + "\n").entries().get(0);
  }

  private static ChecksumCheck.Result verify(Path folder, String name) throws Exception {
    ChecksumList.Entry entry = entry(name);
    return ChecksumCheck.verify(
        folder, entry, entry.algorithm().newDigest(), new CancellationToken(), NONE);
  }

  @Test
  void windowsRefusesAUncNameOffTheListShare() {
    refused("C:\\lists", "\\\\\u212Asrv\\share\\x.txt", WINDOWS, SHARE_REFUSAL);
    refused("C:\\lists", "\\\\srv\\share\\x.txt", WINDOWS, SHARE_REFUSAL);
    refused("C:\\lists", "//srv/share/x.txt", WINDOWS, SHARE_REFUSAL);
    refused("\\\\other\\share\\lists", "\\\\srv\\share\\x.txt", WINDOWS, SHARE_REFUSAL);
    refused("\\\\srv\\docs\\lists", "\\\\srv\\share\\x.txt", WINDOWS, SHARE_REFUSAL);
    refused("C:\\lists", "\\\\srv", WINDOWS, SHARE_REFUSAL);
  }

  @Test
  void windowsRefusesDeviceNamesWhereverTheListIs() {
    for (String name :
        List.of(
            "\\\\.\\PhysicalDrive0",
            "//./pipe/x",
            "\\\\?\\C:\\x.txt",
            "\\\\?\\UNC\\srv\\share\\x.txt",
            "CON",
            "dir\\nul.txt")) {
      refused("C:\\lists", name, WINDOWS, DEVICE_REFUSAL);
      refused("\\\\srv\\share\\lists", name, WINDOWS, DEVICE_REFUSAL);
    }
  }

  @Test
  void windowsKeepsLocalNamesAndNamesOnTheListShare() throws IOException {
    ChecksumCheck.checkName("C:\\lists", "x.txt", WINDOWS);
    ChecksumCheck.checkName("C:\\lists", "D:\\abs\\x.txt", WINDOWS);
    ChecksumCheck.checkName("C:\\lists", "\\root\\x.txt", WINDOWS);
    ChecksumCheck.checkName("\\\\srv\\share\\lists", "sub\\x.txt", WINDOWS);
    ChecksumCheck.checkName("\\\\srv\\share\\lists", "..\\x.txt", WINDOWS);
    ChecksumCheck.checkName("\\\\SRV\\Share\\lists", "\\\\srv\\share\\other\\x.txt", WINDOWS);
  }

  @Test
  void posixKeepsDoubleSlashAndAbsoluteNames() throws IOException {
    ChecksumCheck.checkName("/lists", "//srv/share/x.txt", LINUX);
    ChecksumCheck.checkName("/lists", "/abs/x.txt", LINUX);
    ChecksumCheck.checkName("/lists", "CON", LINUX);
    refused("/lists", "a\0b", LINUX, "Path contains a NUL character");
  }

  @Test
  void anAbsoluteLocalNameVerifies() throws Exception {
    Path file = Files.writeString(temp.resolve("abc.txt"), "abc", StandardCharsets.US_ASCII);
    Path folder = Files.createDirectory(temp.resolve("lists"));
    ChecksumCheck.Result result = verify(folder, file.toAbsolutePath().toString());
    assertEquals(ChecksumCheck.Status.OK, result.status());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aUncNameOffTheListShareIsUnreadableWithItsReason() throws Exception {
    ChecksumCheck.Result result = verify(temp, "\\\\qcaret-mock.invalid\\share\\x.txt");
    assertEquals(ChecksumCheck.Status.UNREADABLE, result.status());
    assertEquals(SHARE_REFUSAL, result.error().getMessage());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void deviceNamesAreUnreadableWithTheirReason() throws Exception {
    for (String name : List.of("\\\\.\\NUL", "\\\\?\\C:\\x.txt", "CON")) {
      ChecksumCheck.Result result = verify(temp, name);
      assertEquals(ChecksumCheck.Status.UNREADABLE, result.status(), name);
      assertEquals(DEVICE_REFUSAL, result.error().getMessage(), name);
    }
  }

  /** Opening a FIFO blocks until a writer appears; the check refuses it before the open. */
  @Test
  void aFifoIsUnreadableWithoutBlocking() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    Path fifo = temp.resolve("pipe");
    Process mkfifo;
    try {
      mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).inheritIO().start();
    } catch (IOException e) {
      assumeTrue(false, "no mkfifo");
      return;
    }
    assumeTrue(mkfifo.waitFor() == 0 && Files.exists(fifo), "mkfifo failed");
    notRegularWithin(temp, "pipe");
  }

  /** {@code /dev/zero} reads without end; the check refuses it before the open. */
  @Test
  void aDeviceIsUnreadableWithoutReading() {
    Path device = Path.of("/dev/zero");
    assumeTrue(Files.exists(device), "no /dev/zero");
    notRegularWithin(temp, device.toString());
  }

  /** Verifies a name under a 30 s bound, so a regression fails instead of hanging the run. */
  private static void notRegularWithin(final Path folder, final String name) {
    ChecksumCheck.Result result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(30),
            new ThrowingSupplier<ChecksumCheck.Result>() {
              @Override
              public ChecksumCheck.Result get() throws Exception {
                return verify(folder, name);
              }
            });
    assertEquals(ChecksumCheck.Status.UNREADABLE, result.status(), name);
    assertEquals("not a regular file", result.error().getMessage(), name);
  }
}
