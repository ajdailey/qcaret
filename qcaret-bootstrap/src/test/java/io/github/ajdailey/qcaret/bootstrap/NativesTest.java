package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class NativesTest {
  private static final String LIB = "libswt-mock-1.so";
  private static final byte[] CONTENT = "mock native library".getBytes(StandardCharsets.US_ASCII);

  @TempDir Path temp;
  private Path jar;

  @BeforeEach
  void writeJar() throws Exception {
    Path fragment = temp.resolve("fragment.jar");
    TestJars.write(fragment, Map.of(LIB, CONTENT));
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CONTENT));
    Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
    entries.put(BootIndex.fragment(Target.LINUX_X86_64), Files.readAllBytes(fragment));
    entries.put(
        BootIndex.digest(Target.LINUX_X86_64, LIB), digest.getBytes(StandardCharsets.US_ASCII));
    jar = temp.resolve("qcaret.jar");
    TestJars.write(jar, entries);
  }

  private void prepare(Path dir, boolean writable) throws Exception {
    try (FileSystem outer = FileSystems.newFileSystem(jar);
        FileSystem fragment =
            FileSystems.newFileSystem(outer.getPath(BootIndex.fragment(Target.LINUX_X86_64)))) {
      Natives.prepare(
          dir, writable, outer, fragment.getPath("/"), Target.LINUX_X86_64, List.of(LIB));
    }
  }

  @Test
  void extractsIntoOwnerOnlyDirectory() throws Exception {
    Path dir = temp.resolve("cache").resolve("swt");
    prepare(dir, true);
    assertArrayEquals(CONTENT, Files.readAllBytes(dir.resolve(LIB)));
    assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
    assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
  }

  @Test
  void reextractsTamperedLibrary() throws Exception {
    Path dir = temp.resolve("swt");
    prepare(dir, true);
    Files.write(dir.resolve(LIB), "tampered".getBytes(StandardCharsets.US_ASCII));
    prepare(dir, true);
    assertArrayEquals(CONTENT, Files.readAllBytes(dir.resolve(LIB)));
  }

  private void prepare(Path dir, Natives.Mover mover) throws Exception {
    try (FileSystem outer = FileSystems.newFileSystem(jar);
        FileSystem fragment =
            FileSystems.newFileSystem(outer.getPath(BootIndex.fragment(Target.LINUX_X86_64)))) {
      Natives.prepare(
          dir, true, outer, fragment.getPath("/"), Target.LINUX_X86_64, List.of(LIB), mover);
    }
  }

  /** A move that loses the race: another start places {@code placed}, then the replace fails. */
  private static Natives.Mover losingMove(final byte[] placed, final IOException failure) {
    return new Natives.Mover() {
      @Override
      public void move(Path source, Path file) throws IOException {
        Files.write(file, placed);
        throw failure;
      }
    };
  }

  @Test
  void aFailedMoveOverAMatchingLibrarySucceeds() throws Exception {
    Path dir = temp.resolve("swt");
    prepare(dir, losingMove(CONTENT, new AccessDeniedException("mock")));
    assertArrayEquals(CONTENT, Files.readAllBytes(dir.resolve(LIB)));
    List<String> names = new ArrayList<String>();
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
      for (Path entry : entries) {
        names.add(entry.getFileName().toString());
      }
    }
    assertEquals(List.of(LIB), names);
  }

  @Test
  void aFailedMoveOverAMismatchedLibraryFails() throws Exception {
    final Path dir = temp.resolve("swt");
    final IOException failure = new AccessDeniedException("mock");
    IOException e =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                prepare(dir, losingMove("tampered".getBytes(StandardCharsets.US_ASCII), failure));
              }
            });
    assertSame(failure, e);
  }

  @Test
  void bundleDirectoryIsVerifiedNotWritten() throws Exception {
    final Path dir = Files.createDirectory(temp.resolve("bundle"));
    Files.write(dir.resolve(LIB), "tampered".getBytes(StandardCharsets.US_ASCII));
    Natives.MismatchException e =
        assertThrows(
            Natives.MismatchException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                prepare(dir, false);
              }
            });
    assertEquals(Natives.mismatchMessage(LIB, dir), e.getMessage());
    assertArrayEquals(
        "tampered".getBytes(StandardCharsets.US_ASCII), Files.readAllBytes(dir.resolve(LIB)));
  }

  @Test
  void rejectsNamesOutsideTheDirectory() {
    for (final String name : List.of("..", "../x.so", "a/b.so", "", "x\0.so")) {
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              Natives.child(temp, name);
            }
          },
          name);
    }
  }
}
