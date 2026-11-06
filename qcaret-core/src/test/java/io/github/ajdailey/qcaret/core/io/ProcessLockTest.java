package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** The lock between processes, within one JVM, and when {@code flock} cannot be linked. */
class ProcessLockTest {
  private static final long BOUND_SECONDS = 60;

  @TempDir Path temp;

  /** One {@link LockMain} with its first line read. */
  private static final class Child implements AutoCloseable {
    private final Process process;
    private final String said;

    Child(Path file) throws IOException {
      process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "--enable-native-access=ALL-UNNAMED",
                  "-cp",
                  System.getProperty("java.class.path"),
                  LockMain.class.getName(),
                  file.toString())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      BufferedReader out =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      said = out.readLine();
    }

    @Override
    public void close() throws InterruptedException {
      process.destroyForcibly();
      assertTrue(process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS));
    }
  }

  /** Windows releases a terminated process's locks shortly after the process ends. */
  private static ProcessLock awaitLock(Path file) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BOUND_SECONDS);
    ProcessLock lock = ProcessLock.tryAcquire(file);
    while (lock == null && System.nanoTime() < deadline) {
      Thread.sleep(50);
      lock = ProcessLock.tryAcquire(file);
    }
    return lock;
  }

  @Test
  void aSecondAcquireInTheJvmIsNullUntilClose() throws IOException {
    Path file = temp.resolve("a.lock");
    try (ProcessLock lock = ProcessLock.tryAcquire(file)) {
      assertNotNull(lock);
      assertNull(ProcessLock.tryAcquire(file));
    }
    try (ProcessLock again = ProcessLock.tryAcquire(file)) {
      assertNotNull(again);
    }
  }

  @Test
  void aSecondCloseLeavesALaterLock() throws IOException {
    Path file = temp.resolve("a.lock");
    ProcessLock first = ProcessLock.tryAcquire(file);
    first.close();
    try (ProcessLock second = ProcessLock.tryAcquire(file)) {
      first.close();
      assertNull(ProcessLock.tryAcquire(file));
      assertNotNull(second);
    }
  }

  @Test
  void aChildIsRefusedThenGetsTheLockAfterClose() throws Exception {
    Path file = temp.resolve("a.lock");
    ProcessLock lock = ProcessLock.tryAcquire(file);
    try (Child child = new Child(file)) {
      assertEquals(LockMain.BUSY, child.said);
    }
    lock.close();
    try (Child child = new Child(file)) {
      assertEquals(LockMain.HOLDING, child.said);
    }
  }

  @Test
  void aKilledHolderReleasesTheLock() throws Exception {
    Path file = temp.resolve("a.lock");
    try (Child child = new Child(file)) {
      assertEquals(LockMain.HOLDING, child.said);
      assertNull(ProcessLock.tryAcquire(file));
    }
    try (ProcessLock lock = awaitLock(file)) {
      assertNotNull(lock);
    }
  }

  /** Closing any channel on a POSIX-locked file drops the process's lock; this lock must stay. */
  @Test
  void readingAndClosingTheFileKeepsTheLock() throws Exception {
    Path file = temp.resolve("a.lock");
    try (ProcessLock lock = ProcessLock.tryAcquire(file)) {
      assertNotNull(lock);
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
        assertEquals(0, channel.size());
      }
      try (Child child = new Child(file)) {
        assertEquals(LockMain.BUSY, child.said);
      }
    }
  }

  @Test
  void aMissingParentThrows() {
    final Path file = temp.resolve("missing").resolve("a.lock");
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            ProcessLock.tryAcquire(file);
          }
        });
  }

  @Test
  void aNonAsciiPathIsTheFileItNames() throws IOException {
    Path file;
    try {
      file = temp.resolve("café-中.lock");
    } catch (InvalidPathException e) {
      assumeTrue(false, "the file name encoding cannot hold the name");
      return;
    }
    try (ProcessLock lock = ProcessLock.tryAcquire(file)) {
      assertNotNull(lock);
      assertTrue(Files.exists(file));
    }
  }

  /** Every thread reads its own {@code errno}: a failed call never reports success. */
  @Test
  void racingThreadsLeaveOneHolder() throws Exception {
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int round = 0; round < 100; round++) {
        final Path file = temp.resolve("race-" + round + ".lock");
        List<Future<ProcessLock>> claims = new ArrayList<Future<ProcessLock>>();
        for (int i = 0; i < threads; i++) {
          claims.add(
              pool.submit(
                  new Callable<ProcessLock>() {
                    @Override
                    public ProcessLock call() throws IOException {
                      return ProcessLock.tryAcquire(file);
                    }
                  }));
        }
        int holders = 0;
        for (Future<ProcessLock> claim : claims) {
          ProcessLock lock = claim.get(BOUND_SECONDS, TimeUnit.SECONDS);
          if (lock != null) {
            holders++;
          }
        }
        assertTrue(holders >= 1, "round " + round);
        assertNull(ProcessLock.tryAcquire(file), "round " + round);
        for (Future<ProcessLock> claim : claims) {
          ProcessLock lock = claim.get();
          if (lock != null) {
            lock.close();
          }
        }
        assertEquals(1, holders, "round " + round);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void theChannelPathRefusesASecondAcquireInTheJvm() throws IOException {
    Path file = temp.resolve("a.lock");
    try (ProcessLock lock = ProcessLock.onChannel(file)) {
      assertNotNull(lock);
      assertNull(ProcessLock.onChannel(file));
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void theFlockPathOpensAFileOnlyItsOwnerReaches() throws IOException {
    Path file = temp.resolve("a.lock");
    try (ProcessLock lock = ProcessLock.tryAcquire(file)) {
      assertNotNull(lock);
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
  }

  /** A JDK that lacks the API. */
  @Test
  void aMissingApiFailsTheBinding() {
    assertUnavailable("io.github.ajdailey.qcaret.core.io.missing.");
  }

  /** A JDK where a method the binding needs differs. */
  @Test
  void aChangedApiFailsTheBinding() {
    assertUnavailable(ProcessLockTest.class.getName() + "$Changed$");
  }

  /** A JDK that refuses the call, as it does when native access is denied. */
  @Test
  void aRefusedCallFailsTheBinding() {
    assertUnavailable(ProcessLockTest.class.getName() + "$Refused$");
  }

  private static void assertUnavailable(final String foreign) {
    IOException thrown =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws IOException {
                Flock.bind(foreign, Flock.Constants.LINUX);
              }
            });
    assertTrue(
        thrown.getMessage().startsWith("flock is not available on Java "), thrown.toString());
  }

  @Test
  void theConstantsFollowTheSystem() throws IOException {
    assertEquals(Flock.Constants.LINUX, Flock.Constants.of("Linux"));
    assertEquals(Flock.Constants.MACOS, Flock.Constants.of("Mac OS X"));
    assertEquals(0x1000202, Flock.Constants.MACOS.open());
    assertEquals(0x80042, Flock.Constants.LINUX.open());
    assertFalse(Flock.Constants.LINUX.wouldBlock() == Flock.Constants.MACOS.wouldBlock());
    assertThrows(
        IOException.class,
        new Executable() {
          @Override
          public void execute() throws IOException {
            Flock.Constants.of("Plan 9");
          }
        });
  }

  /** Stands for an API whose linker lacks {@code nativeLinker}. */
  static final class Changed {
    private Changed() {}

    static final class Linker {
      private Linker() {}
    }
  }

  /** Stands for an API whose linker refuses the caller. */
  static final class Refused {
    private Refused() {}

    static final class Linker {
      private Linker() {}

      public static Object nativeLinker() {
        throw new IllegalCallerException("mock");
      }
    }
  }
}
