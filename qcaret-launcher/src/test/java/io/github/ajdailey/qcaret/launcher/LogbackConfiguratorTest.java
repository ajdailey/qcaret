package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.ajdailey.qcaret.app.cli.LogLevel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogbackConfiguratorTest {
  @AfterEach
  void restoreDefaultLevel() {
    LogbackConfigurator.setLevel(LogLevel.WARN);
  }

  private static Logger root() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  @Test
  void plantedLogbackXmlIsIgnored() {
    assertNotNull(
        LogbackConfiguratorTest.class.getClassLoader().getResource("logback.xml"),
        "the planted logback.xml must be on the test class path");
    Logger root = root();
    assertEquals(Level.INFO, root.getLevel());
    assertNotNull(root.getAppender("stderr"));
    assertNotNull(root.getAppender("recent"));
    assertNull(root.getAppender("planted"));
  }

  @Test
  void recentKeepsInfoAndNeutralizesLineBreaks() {
    LoggerFactory.getLogger("mock.recent").info("first\nforged");
    LoggerFactory.getLogger("mock.recent").debug("hidden");
    List<String> lines = LogbackConfigurator.RECENT.snapshot();
    String last = lines.get(lines.size() - 1);
    assertTrue(last.endsWith("mock.recent - first_forged"), last);
  }

  @Test
  void debugLevelReachesRecentLines() {
    LogbackConfigurator.setLevel(LogLevel.DEBUG);
    assertEquals(Level.DEBUG, root().getLevel());
    LoggerFactory.getLogger("mock.recent").debug("shown");
    List<String> lines = LogbackConfigurator.RECENT.snapshot();
    assertTrue(lines.get(lines.size() - 1).endsWith("shown"));
    LogbackConfigurator.setLevel(LogLevel.ERROR);
    assertFalse(root().isDebugEnabled());
    assertEquals(Level.INFO, root().getLevel());
  }
}
