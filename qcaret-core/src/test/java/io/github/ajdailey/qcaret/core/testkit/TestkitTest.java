package io.github.ajdailey.qcaret.core.testkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

class TestkitTest {
  @TempDir Path temp;

  @Test
  void pollReturnsOnceTheConditionHoldsOrTheBoundPasses() throws InterruptedException {
    final AtomicInteger checks = new AtomicInteger();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return checks.incrementAndGet() == 3;
              }
            },
            Duration.ofSeconds(10)));
    assertFalse(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return false;
              }
            },
            Duration.ofMillis(50)));
  }

  @Test
  void deletesANestedTreeAndToleratesAMissingRoot() throws Exception {
    Path root = Files.createDirectories(temp.resolve("a").resolve("b"));
    Files.writeString(root.resolve("file"), "x");
    Files.writeString(temp.resolve("a").resolve("top"), "y");
    TempTrees.delete(temp.resolve("a"), Duration.ofSeconds(5));
    assertFalse(Files.exists(temp.resolve("a")));
    TempTrees.delete(temp.resolve("a"), Duration.ofSeconds(5));
  }

  @Test
  void rethrowsOnceTheBoundPasses() throws IOException {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"), "no POSIX");
    final Path locked = Files.createDirectory(temp.resolve("locked"));
    Files.writeString(locked.resolve("file"), "x");
    Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-x------"));
    try {
      assumeTrue(!Files.isWritable(locked), "running with permission to write anyway");
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Exception {
              TempTrees.delete(locked, Duration.ofMillis(250));
            }
          });
    } finally {
      Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
    }
  }
}
