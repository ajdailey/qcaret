package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.LogLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogConfiguratorTest {
  @AfterEach
  void restoreDefaultLevel() {
    LogConfigurator.setLevel(LogLevel.WARN);
  }

  @Test
  void plantedConfigurationIsDiscarded() {
    String planted = System.getProperty("java.util.logging.config.file");
    assertNotNull(planted, "surefire must plant java.util.logging.config.file");
    assertTrue(Files.isRegularFile(Path.of(planted)), planted);
    LogConfigurator.install();
    assertArrayEquals(
        new Handler[] {LogConfigurator.stderr(), LogConfigurator.recent()},
        LogConfigurator.handlers());
    assertEquals(Level.INFO, LogConfigurator.level());
    assertNull(LogManager.getLogManager().getProperty("mock.planted.level"));
    Logger plantedLogger = Logger.getLogger("mock.planted");
    assertNull(plantedLogger.getLevel());
    assertEquals(0, plantedLogger.getHandlers().length);
  }

  @Test
  void recentKeepsInfoAndNeutralizesLineBreaks() {
    LoggerFactory.getLogger("mock.recent").info("first\nforged");
    LoggerFactory.getLogger("mock.recent").debug("hidden");
    List<String> lines = LogConfigurator.RECENT.snapshot();
    String last = lines.get(lines.size() - 1);
    assertTrue(last.endsWith("mock.recent - first_forged"), last);
  }

  @Test
  void debugLevelReachesRecentLinesForQcaretLoggersOnly() {
    org.slf4j.Logger qcaret = LoggerFactory.getLogger(LogConfigurator.QCARET + ".mock");
    LogConfigurator.setLevel(LogLevel.DEBUG);
    assertEquals(Level.FINE, LogConfigurator.level());
    qcaret.debug("shown");
    List<String> lines = LogConfigurator.RECENT.snapshot();
    assertTrue(lines.get(lines.size() - 1).endsWith("shown"));
    assertFalse(LoggerFactory.getLogger("jdk.mock").isDebugEnabled());
    LogConfigurator.setLevel(LogLevel.ERROR);
    assertFalse(qcaret.isDebugEnabled());
    assertEquals(Level.INFO, LogConfigurator.level());
    assertEquals(Level.SEVERE, LogConfigurator.stderr().getLevel());
  }

  @Test
  void aMessageWithLineBreaksStaysOneLine() {
    LogRecord record = new LogRecord(Level.WARNING, "a\rb\nc\u0085d\u2028e\u2029f");
    record.setLoggerName("mock.recent");
    String text = LogConfigurator.FORMAT.format(record);
    assertTrue(text.endsWith(System.lineSeparator()), text);
    String line = text.substring(0, text.length() - System.lineSeparator().length());
    assertTrue(
        line.matches(
            "\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d,\\d{3} WARN  \\[[^\\]]+\\] mock\\.recent"
                + " - a_b_c_d_e_f"),
        line);
  }

  @Test
  void aThrowableFollowsTheLine() {
    LogRecord record = new LogRecord(Level.SEVERE, "failed");
    record.setLoggerName("mock.recent");
    record.setThrown(new IllegalStateException("mock cause"));
    String[] lines = LogConfigurator.FORMAT.format(record).split("\\R");
    assertTrue(
        lines[0].endsWith(
            " ERROR [" + Thread.currentThread().getName() + "] mock.recent" + " - failed"),
        lines[0]);
    assertEquals("java.lang.IllegalStateException: mock cause", lines[1]);
    assertTrue(lines[2].startsWith("\tat "), lines[2]);
  }

  @Test
  void loggerNamesShortenFromTheLeft() {
    assertEquals("mock.recent", LineFormatter.abbreviate("mock.recent", 20));
    assertEquals(
        "i.g.a.q.l.Launcher",
        LineFormatter.abbreviate("io.github.ajdailey.qcaret.launcher.Launcher", 20));
    assertEquals("i.g.a.qcaret.Foo", LineFormatter.abbreviate("io.github.ajdailey.qcaret.Foo", 20));
    assertEquals(
        "AVeryLongClassNameWithoutPackage",
        LineFormatter.abbreviate("AVeryLongClassNameWithoutPackage", 20));
  }

  @Test
  void levelsMapBothWays() {
    LogLevel[] levels = LogLevel.values();
    Level[] jul = {Level.SEVERE, Level.WARNING, Level.INFO, Level.FINE, Level.FINEST};
    for (int i = 0; i < levels.length; i++) {
      assertEquals(jul[i], LogConfigurator.julLevel(levels[i]));
      assertEquals(levels[i].name(), LineFormatter.levelName(jul[i]));
    }
    assertEquals("INFO", LineFormatter.levelName(Level.CONFIG));
    assertEquals("TRACE", LineFormatter.levelName(Level.FINER));
  }
}
