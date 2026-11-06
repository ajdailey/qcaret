package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class AtomicFilesTest {
  @TempDir Path temp;

  private List<String> names() throws IOException {
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(temp)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    return names;
  }

  /** A move refused by the file system until {@code refusals} calls have failed. */
  private static AtomicFiles.Move refused(final int refusals, final AtomicInteger calls) {
    return new AtomicFiles.Move() {
      @Override
      public void run() throws IOException {
        if (calls.incrementAndGet() <= refusals) {
          throw new AccessDeniedException("target");
        }
      }
    };
  }

  @Test
  void aRefusedMoveIsTriedAgain() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    AtomicFiles.tryMove(refused(2, calls), 3);
    assertEquals(3, calls.get());
  }

  @Test
  void aMoveRefusedEveryTimeThrowsTheRefusalAfterTheLastAttempt() {
    final AtomicInteger calls = new AtomicInteger();
    assertThrows(
        AccessDeniedException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.tryMove(refused(5, calls), 3);
          }
        });
    assertEquals(3, calls.get());
  }

  @Test
  void aMoveFailingForAnotherReasonIsNotTriedAgain() {
    final AtomicInteger calls = new AtomicInteger();
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.tryMove(
                new AtomicFiles.Move() {
                  @Override
                  public void run() throws IOException {
                    calls.incrementAndGet();
                    throw new IOException("disk");
                  }
                },
                3);
          }
        });
    assertEquals(1, calls.get());
  }

  @Test
  void writesAndReplacesLeavingNoTemporaryFile() throws IOException {
    Path file = temp.resolve("qcaret.conf");
    AtomicFiles.write(file, "first".getBytes(StandardCharsets.US_ASCII));
    AtomicFiles.write(file, "second".getBytes(StandardCharsets.US_ASCII));
    assertArrayEquals("second".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(file));
    assertEquals(List.of("qcaret.conf"), names());
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }

  @Test
  void aFailedMoveKeepsTheTargetAndRemovesTheTemporaryFile() throws IOException {
    final Path dir = Files.createDirectory(temp.resolve("target-is-a-directory"));
    Files.writeString(dir.resolve("child"), "x");
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.write(dir, new byte[] {1});
          }
        });
    assertEquals(List.of("target-is-a-directory"), names());
  }

  @Test
  void writesAStreamAndAFailedReadKeepsTheTarget() throws IOException {
    final Path file = temp.resolve("guide.html");
    AtomicFiles.write(file, new ByteArrayInputStream("page".getBytes(StandardCharsets.US_ASCII)));
    assertEquals("page", Files.readString(file, StandardCharsets.US_ASCII));
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            AtomicFiles.write(
                file,
                new InputStream() {
                  @Override
                  public int read() throws IOException {
                    throw new IOException("mock read failure");
                  }
                });
          }
        });
    assertEquals("page", Files.readString(file, StandardCharsets.US_ASCII));
    assertEquals(List.of("guide.html"), names());
  }
}
