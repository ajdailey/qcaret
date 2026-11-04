package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class SafeSaveTest {
  @TempDir Path temp;

  private static ContentWriter text(final String content) {
    return new ContentWriter() {
      @Override
      public void write(OutputStream out) throws IOException {
        out.write(content.getBytes(StandardCharsets.UTF_8));
      }
    };
  }

  private boolean posix() {
    return temp.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private List<String> names() throws IOException {
    List<String> out = new ArrayList<String>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(temp)) {
      for (Path p : files) {
        out.add(p.getFileName().toString());
      }
    }
    out.sort(null);
    return out;
  }

  @Test
  void replacesTheFileAtomicallyAndKeepsItsPermissions() throws Exception {
    Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    if (posix()) {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    }
    FileStamp baseline = FileStamp.read(file);
    FileStamp saved = SafeSave.save(file, baseline, text("new"), new CancellationToken());
    assertEquals("new", Files.readString(file));
    assertEquals(FileStamp.read(file), saved);
    assertEquals(List.of("a.txt"), names());
    if (posix()) {
      assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
      assertNotEquals(baseline.fileKey(), saved.fileKey());
    }
  }

  @Test
  void createsANewFile() throws Exception {
    Path file = temp.resolve("new.txt");
    SafeSave.save(file, null, text("x"), new CancellationToken());
    assertEquals("x", Files.readString(file));
  }

  @Test
  void anExternalChangeStopsTheSaveBeforeAnyWrite() throws Exception {
    final Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    final FileStamp baseline = FileStamp.read(file);
    Files.writeString(file, "theirs, longer");
    ExternalModificationException e =
        assertThrows(
            ExternalModificationException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                SafeSave.save(file, baseline, text("mine"), new CancellationToken());
              }
            });
    assertEquals(FileStamp.read(file), e.current());
    assertEquals("theirs, longer", Files.readString(file));
    assertEquals(List.of("a.txt"), names());
  }

  @Test
  void aCancelBeforeTheMoveLeavesTheTargetAndNoTemporaryFile() throws Exception {
    final Path file = temp.resolve("a.txt");
    Files.writeString(file, "old");
    final FileStamp baseline = FileStamp.read(file);
    final CancellationToken token = new CancellationToken();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            SafeSave.save(
                file,
                baseline,
                new ContentWriter() {
                  @Override
                  public void write(OutputStream out) throws IOException {
                    out.write('x');
                    token.cancel();
                  }
                },
                token);
          }
        });
    assertEquals("old", Files.readString(file));
    assertEquals(List.of("a.txt"), names());
  }

  @Test
  void aSymbolicLinkStaysALink() throws Exception {
    Path target = temp.resolve("target.txt");
    Files.writeString(target, "old");
    Path link = temp.resolve("link.txt");
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "no symbolic links: " + e);
    }
    SafeSave.save(link, FileStamp.read(link), text("new"), new CancellationToken());
    assertTrue(Files.isSymbolicLink(link));
    assertEquals("new", Files.readString(target));
  }

  @Test
  void aHardLinkedFileIsWrittenInPlace() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("unix"));
    Path file = temp.resolve("a.txt");
    Files.writeString(file, "old content");
    Path other = temp.resolve("b.txt");
    Files.createLink(other, file);
    FileStamp baseline = FileStamp.read(file);
    FileStamp saved = SafeSave.save(file, baseline, text("new"), new CancellationToken());
    assertEquals("new", Files.readString(other));
    assertEquals(baseline.fileKey(), saved.fileKey());
    assertFalse(names().size() > 2);
  }

  @Test
  void binaryDetection() {
    byte[] text = "plain\ttext\r\n\f".getBytes(StandardCharsets.US_ASCII);
    assertFalse(BinaryDetector.isBinary(text, text.length, false));
    byte[] nul = {'a', 0, 'b'};
    assertTrue(BinaryDetector.isBinary(nul, nul.length, false));
    assertFalse(BinaryDetector.isBinary(nul, nul.length, true));
    byte[] controls = {'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 0x1b, 0x1b};
    assertTrue(BinaryDetector.isBinary(controls, controls.length, false));
    assertFalse(BinaryDetector.isBinary(controls, 10, false));
  }
}
