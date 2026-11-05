package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.LogLevel;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Configures java.util.logging, the SLF4J backend, in code when the class initializes: {@link Main}
 * calls {@link #install} before anything logs, and any other first use configures the same way.
 * Initialization sets {@code java.util.logging.manager} to {@link RetainingLogManager} unless it is
 * set, then calls {@code LogManager.reset()}, which drops the handlers, levels and properties read
 * from {@code $JAVA_HOME/conf/logging.properties} or a {@code java.util.logging.config.*} property,
 * and installs two root handlers: {@code stderr} at WARN, and {@code recent}, which feeds {@link
 * #RECENT} at INFO. Both format with {@link LineFormatter}.
 *
 * <p>{@link #setLevel} applies {@code -d} ({@link #julLevel}) to stderr, and to the recent lines
 * when finer than INFO. {@link FileLog} adds a third handler while the debug log file is open. The
 * level of the {@link #QCARET} loggers is the finest of them: the file takes FINE (DEBUG), or
 * {@code -d} when finer. Every other logger, such as the JDK's own, stays at INFO.
 */
public final class LogConfigurator {
  /** The recent lines, for Show Log. */
  public static final RecentLog RECENT = new RecentLog(RecentLog.LINES, RecentLog.LINE_MAX_CHARS);

  /** The formatter every handler uses. */
  static final LineFormatter FORMAT = new LineFormatter();

  /** The logger namespace that {@code -d} and the debug log file set. */
  static final String QCARET = "io.github.ajdailey.qcaret";

  private static final String MANAGER_PROPERTY = "java.util.logging.manager";

  /** Set first: creating a {@code Handler} initializes the {@code LogManager}. */
  private static final LogManager MANAGER = startManager();

  /** The root logger, held so its level and handlers stay. */
  private static final Logger ROOT = Logger.getLogger("");

  /** QCaret's loggers' parent, held so its level stays. */
  private static final Logger QCARET_LOGGER = Logger.getLogger(QCARET);

  private static final StderrHandler STDERR = new StderrHandler();
  private static final RecentHandler TO_RECENT = new RecentHandler(RECENT);
  private static volatile Level consoleLevel = Level.INFO;
  private static volatile boolean fileAttached;

  static {
    STDERR.setFormatter(FORMAT);
    STDERR.setLevel(Level.WARNING);
    TO_RECENT.setFormatter(FORMAT);
    TO_RECENT.setLevel(Level.INFO);
    ROOT.addHandler(STDERR);
    ROOT.addHandler(TO_RECENT);
    ROOT.setLevel(Level.INFO);
    QCARET_LOGGER.setLevel(Level.INFO);
    if (MANAGER instanceof RetainingLogManager retaining) {
      retaining.retain();
    }
  }

  private static LogManager startManager() {
    if (System.getProperty(MANAGER_PROPERTY) == null) {
      System.setProperty(MANAGER_PROPERTY, RetainingLogManager.class.getName());
    }
    LogManager manager = LogManager.getLogManager();
    manager.reset();
    return manager;
  }

  private LogConfigurator() {}

  /** Configures logging; any later call does nothing. Call before the first logger is used. */
  public static void install() {
    // The static initializer configures.
  }

  /**
   * Applies {@code -d}: stderr logs at {@code level}; the recent lines keep INFO or {@code level},
   * whichever is finer.
   *
   * @param level the level
   */
  static void setLevel(LogLevel level) {
    Level stderr = julLevel(level);
    Level recent = stderr.intValue() >= Level.INFO.intValue() ? Level.INFO : stderr;
    STDERR.setLevel(stderr);
    TO_RECENT.setLevel(recent);
    consoleLevel = recent;
    applyLevel();
  }

  /**
   * Returns the JUL level for a {@code -d} level: ERROR SEVERE, WARN WARNING, INFO INFO, DEBUG
   * FINE, TRACE FINEST, as slf4j-jdk14 maps the SLF4J levels.
   *
   * @param level the level
   * @return the JUL level
   */
  static Level julLevel(LogLevel level) {
    return switch (level) {
      case ERROR -> Level.SEVERE;
      case WARN -> Level.WARNING;
      case INFO -> Level.INFO;
      case DEBUG -> Level.FINE;
      case TRACE -> Level.FINEST;
    };
  }

  /**
   * Attaches or removes the debug log file's handler and lowers the root level to FINE while it is
   * attached.
   *
   * @param file the handler
   * @param attached true to attach
   */
  static void setFile(Handler file, boolean attached) {
    if (attached) {
      file.setFormatter(FORMAT);
      ROOT.addHandler(file);
    } else {
      ROOT.removeHandler(file);
    }
    fileAttached = attached;
    applyLevel();
  }

  /**
   * Returns the root logger's handlers.
   *
   * @return a copy
   */
  static Handler[] handlers() {
    return ROOT.getHandlers();
  }

  /**
   * Returns the stderr handler.
   *
   * @return the handler
   */
  static Handler stderr() {
    return STDERR;
  }

  /**
   * Returns the recent-lines handler.
   *
   * @return the handler
   */
  static Handler recent() {
    return TO_RECENT;
  }

  /**
   * Returns the level of QCaret's loggers.
   *
   * @return the level
   */
  static Level level() {
    return QCARET_LOGGER.getLevel();
  }

  private static synchronized void applyLevel() {
    Level level = consoleLevel;
    if (fileAttached && level.intValue() > Level.FINE.intValue()) {
      level = Level.FINE;
    }
    QCARET_LOGGER.setLevel(level);
  }
}
