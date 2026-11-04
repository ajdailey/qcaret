package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.status.ErrorStatus;
import io.github.ajdailey.qcaret.app.cli.LogLevel;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The debug log file: location, level, detach and the write-failure path. */
class FileLogTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Logger LOG = LoggerFactory.getLogger(FileLogTest.class);

  @TempDir Path temp;

  private FileLog log;

  @AfterEach
  void detach() {
    if (log != null) {
      log.detach();
    }
    LogbackConfigurator.setLevel(LogLevel.WARN);
  }

  private static ch.qos.logback.classic.Logger root() {
    return ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  private boolean fileContains(final Path file, final String text) throws InterruptedException {
    return Poll.until(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return Files.exists(file)
                  && Files.readString(file, StandardCharsets.UTF_8).contains(text);
            } catch (IOException e) {
              return false;
            }
          }
        },
        BOUND);
  }

  @Test
  void writesDebugLinesToLogsInTheSettingsDirectory() throws Exception {
    LogbackConfigurator.setLevel(LogLevel.WARN);
    assertEquals(Level.INFO, root().getLevel());
    log = new FileLog(temp);
    assertEquals(temp.resolve("logs"), log.directory());
    log.attach();
    assertTrue(log.attached());
    assertEquals(Level.DEBUG, root().getLevel());
    LOG.debug("mock debug line");
    Path file = temp.resolve("logs").resolve("qcaret.log");
    assertTrue(fileContains(file, "mock debug line"));
    if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertEquals(
          "rwx------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(temp.resolve("logs"))));
    }
    log.detach();
    assertFalse(log.attached());
    assertEquals(Level.INFO, root().getLevel());
    LOG.info("after detach");
    assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("after detach"));
  }

  // Log file per secondary instance.

  @Test
  void aProcessWithoutTheHandoffLockWritesItsOwnFile() throws Exception {
    assertEquals("qcaret.log", FileLog.fileName(true, 4242));
    assertEquals("qcaret-4242.log", FileLog.fileName(false, 4242));
    log = new FileLog(temp, FileLog.fileName(false, 4242));
    log.attach();
    LOG.debug("mock secondary line");
    assertTrue(
        fileContains(temp.resolve("logs").resolve("qcaret-4242.log"), "mock secondary line"));
    assertFalse(Files.exists(temp.resolve("logs").resolve("qcaret.log")));
  }

  @Test
  void traceFromTheCommandLineStaysFinerThanTheFile() throws Exception {
    LogbackConfigurator.setLevel(LogLevel.TRACE);
    log = new FileLog(temp);
    log.attach();
    assertEquals(Level.TRACE, root().getLevel());
  }

  @Test
  void aWriteFailureDetachesTheFile() throws Exception {
    log = new FileLog(temp);
    log.attach();
    AsyncAppender writer = (AsyncAppender) root().getAppender("qcaret-logfile");
    assertNotNull(writer);
    Appender<?> file = writer.getAppender("qcaret-file");
    assertNotNull(file);
    ((LoggerContext) LoggerFactory.getILoggerFactory())
        .getStatusManager()
        .add(new ErrorStatus("mock write failure", file));
    assertFalse(log.attached());
    assertEquals(null, root().getAppender("qcaret-logfile"));
    boolean reported = false;
    for (String line : LogbackConfigurator.RECENT.snapshot()) {
      reported |= line.contains("Debug log file closed after a write failure: mock write failure");
    }
    assertTrue(reported);
  }

  /** Creates a file in the logs folder modified at the given time. */
  private Path logFile(String name, Instant modified) throws IOException {
    Path file = Files.writeString(temp.resolve("logs").resolve(name), "mock line\n");
    Files.setLastModifiedTime(file, FileTime.from(modified));
    return file;
  }

  @Test
  void pruneDeletesOnlyOldPerProcessFilesOfEndedProcesses() throws Exception {
    Files.createDirectories(temp.resolve("logs"));
    Instant now = Instant.parse("2026-11-02T12:00:00Z");
    Instant old = now.minus(FileLog.PRUNE_AGE).minusSeconds(60);
    Instant young = now.minus(FileLog.PRUNE_AGE).plusSeconds(60);
    Path ended = logFile("qcaret-101.log", old);
    Path recent = logFile("qcaret-102.log", young);
    Path live = logFile("qcaret-103.log", old);
    Path primary = logFile("qcaret.log", old);
    Path rolled = logFile("qcaret-101.log.1", old);
    Path liveRolled = logFile("qcaret-103.log.1", old);
    Path rolledTwice = logFile("qcaret-101.log.2", old);
    Path other = logFile("qcaret-mock.log", old);
    Path text = logFile("notes.txt", old);
    Path folder = Files.createDirectory(temp.resolve("logs").resolve("qcaret-104.log"));
    Files.setLastModifiedTime(folder, FileTime.from(old));
    log = new FileLog(temp, FileLog.fileName(true, 1));
    LongPredicate running =
        new LongPredicate() {
          @Override
          public boolean test(long pid) {
            return pid == 103;
          }
        };
    assertEquals(2, log.prune(now, running));
    assertFalse(Files.exists(ended));
    assertFalse(Files.exists(rolled));
    for (Path kept : List.of(recent, live, liveRolled, rolledTwice, primary, other, text, folder)) {
      assertTrue(Files.exists(kept), kept.toString());
    }
    assertEquals(0, log.prune(now, running));
  }

  @Test
  void pruneWithoutTheLogsFolderDeletesNothing() throws Exception {
    log = new FileLog(temp);
    assertEquals(0, log.prune(Instant.now(), FileLog::running));
    assertFalse(Files.exists(temp.resolve("logs")));
    assertTrue(FileLog.running(ProcessHandle.current().pid()));
  }
}
