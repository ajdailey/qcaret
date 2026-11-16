package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The relay's hold: order, the bound, release, and nothing kept without a hold. */
class FileRelayHandlerTest {
  @TempDir Path temp;

  private final FileRelayHandler relay = new FileRelayHandler();
  private Path path;
  private LogFileHandler file;

  @BeforeEach
  void open() throws IOException {
    relay.setFormatter(LogConfigurator.FORMAT);
    path = temp.resolve("mock.log");
    file =
        new LogFileHandler(
            path,
            FileLog.ROLL_BYTES,
            new LogFileHandler.Failure() {
              @Override
              public void failed(LogFileHandler handler, IOException error) {
                throw new AssertionError(error);
              }

              @Override
              public void rollFailed(LogFileHandler handler, IOException error) {
                throw new AssertionError(error);
              }
            });
    file.setFormatter(LogConfigurator.FORMAT);
  }

  @AfterEach
  void close() {
    file.close();
  }

  private static LogRecord record(String message) {
    LogRecord record = new LogRecord(Level.FINE, message);
    record.setLoggerName("mock.relay");
    return record;
  }

  private List<String> lines() throws IOException {
    return Files.readAllLines(path, StandardCharsets.UTF_8);
  }

  @Test
  void heldRecordsReachTheFileFirstAndOnce() throws IOException {
    relay.hold();
    relay.publish(record("first"));
    relay.publish(record("second"));
    assertEquals(0, lines().size());
    relay.setTarget(file);
    relay.publish(record("third"));
    relay.release();
    relay.publish(record("fourth"));
    List<String> lines = lines();
    assertEquals(4, lines.size(), lines.toString());
    String[] expected = {"first", "second", "third", "fourth"};
    for (int i = 0; i < expected.length; i++) {
      assertTrue(lines.get(i).endsWith("mock.relay - " + expected[i]), lines.get(i));
    }
  }

  @Test
  void recordsPastTheBoundAreDroppedAndCountedInOneWarn() throws IOException {
    String message = "x".repeat(1000);
    int published = FileRelayHandler.HELD_CHARS / 1000 + 10;
    relay.hold();
    for (int i = 0; i < published; i++) {
      relay.publish(record(message));
    }
    relay.setTarget(file);
    List<String> lines = lines();
    int kept = lines.size() - 1;
    int dropped = published - kept;
    assertTrue(dropped > 0, "nothing dropped");
    String last = lines.get(kept);
    assertTrue(
        last.contains(" WARN ")
            && last.endsWith(
                dropped
                    + " startup log records past "
                    + FileRelayHandler.HELD_CHARS
                    + " characters not kept"),
        last);
    long chars = 0;
    for (int i = 0; i < kept; i++) {
      chars += lines.get(i).length() + System.lineSeparator().length();
    }
    assertTrue(chars <= FileRelayHandler.HELD_CHARS, Long.toString(chars));
  }

  @Test
  void releaseDiscardsTheHeldRecords() throws IOException {
    relay.hold();
    relay.publish(record("discarded"));
    relay.release();
    relay.setTarget(file);
    relay.publish(record("kept"));
    List<String> lines = lines();
    assertEquals(1, lines.size(), lines.toString());
    assertTrue(lines.get(0).endsWith("kept"), lines.get(0));
  }

  @Test
  void withoutAHoldOrAFileNothingIsKept() throws IOException {
    relay.publish(record("dropped"));
    relay.setTarget(file);
    assertEquals(0, lines().size());
    relay.setTarget(null);
    relay.publish(record("after detach"));
    assertEquals(0, lines().size());
  }
}
