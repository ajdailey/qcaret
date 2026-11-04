package io.github.ajdailey.qcaret.launcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ConfiguratorRank;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.spi.ContextAwareBase;
import io.github.ajdailey.qcaret.app.cli.LogLevel;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import org.slf4j.LoggerFactory;

/**
 * Configures logback in code. Registered through {@code META-INF/services} at the top rank and
 * returns {@code DO_NOT_INVOKE_NEXT_IF_ANY}, so logback never reads a {@code logback.xml} or {@code
 * logback.configurationFile}, and no logback condition is ever evaluated.
 *
 * <p>Two appenders: {@code stderr} at WARN, and {@code recent}, which feeds {@link #RECENT} at
 * INFO. {@link #setLevel} applies {@code -d} to stderr, and to the recent lines when finer than
 * INFO. {@link FileLog} adds a third while the debug log file is open. The root level is the finest
 * of them: the file takes DEBUG, or {@code -d} when finer.
 */
@ConfiguratorRank(ConfiguratorRank.CUSTOM_TOP_PRIORITY)
public final class LogbackConfigurator extends ContextAwareBase implements Configurator {
  /**
   * The encoder pattern. {@code %replace} turns CR, LF, NEL, U+2028 and U+2029 in a message into
   * {@code _}, behind the call sites' own {@code LogText.oneLine}.
   */
  static final String PATTERN =
      "%d{ISO8601} %-5level [%thread] %logger{20} -"
          + " %replace(%msg){'[\\r\\n\\u0085\\u2028\\u2029]', '_'}%n%ex";

  /** The recent lines, for Show Log. */
  public static final RecentLog RECENT = new RecentLog(RecentLog.LINES, RecentLog.LINE_MAX_CHARS);

  private static volatile ThresholdFilter stderrFilter;
  private static volatile ThresholdFilter recentFilter;
  private static volatile Level consoleLevel = Level.INFO;
  private static volatile boolean fileAttached;

  /** Creates the configurator; logback calls it through {@code ServiceLoader}. */
  public LogbackConfigurator() {
    super();
  }

  @Override
  public ExecutionStatus configure(LoggerContext context) {
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(PATTERN);
    encoder.start();

    ThresholdFilter toStderr = threshold(context, Level.WARN);
    ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<ILoggingEvent>();
    console.setContext(context);
    console.setName("stderr");
    console.setTarget("System.err");
    console.setEncoder(encoder);
    console.addFilter(toStderr);
    console.start();

    PatternLayout layout = new PatternLayout();
    layout.setContext(context);
    layout.setPattern(PATTERN);
    layout.start();
    ThresholdFilter toRecent = threshold(context, Level.INFO);
    RecentAppender recent = new RecentAppender(layout, RECENT);
    recent.setContext(context);
    recent.setName("recent");
    recent.addFilter(toRecent);
    recent.start();

    ch.qos.logback.classic.Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.detachAndStopAllAppenders();
    root.addAppender(console);
    root.addAppender(recent);
    root.setLevel(Level.INFO);
    useFilters(toStderr, toRecent);
    return ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }

  /**
   * Applies {@code -d}: stderr logs at {@code level}; the recent lines keep INFO or {@code level},
   * whichever is finer.
   *
   * @param level the level
   */
  static void setLevel(LogLevel level) {
    Level stderr = Level.toLevel(level.name(), Level.WARN);
    Level recent = stderr.isGreaterOrEqual(Level.INFO) ? Level.INFO : stderr;
    ThresholdFilter toStderr = stderrFilter;
    ThresholdFilter toRecent = recentFilter;
    if (toStderr != null) {
      toStderr.setLevel(stderr.toString());
    }
    if (toRecent != null) {
      toRecent.setLevel(recent.toString());
    }
    consoleLevel = recent;
    applyRootLevel();
  }

  /**
   * Records whether the debug log file is attached, which lowers the root level to DEBUG.
   *
   * @param attached true while {@link FileLog} writes
   */
  static void setFileAttached(boolean attached) {
    fileAttached = attached;
    applyRootLevel();
  }

  private static void applyRootLevel() {
    Level level = consoleLevel;
    if (fileAttached && level.isGreaterOrEqual(Level.DEBUG)) {
      level = Level.DEBUG;
    }
    ch.qos.logback.classic.Logger root =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    root.setLevel(level);
  }

  private static void useFilters(ThresholdFilter toStderr, ThresholdFilter toRecent) {
    stderrFilter = toStderr;
    recentFilter = toRecent;
  }

  private static ThresholdFilter threshold(LoggerContext context, Level level) {
    ThresholdFilter filter = new ThresholdFilter();
    filter.setContext(context);
    filter.setLevel(level.toString());
    filter.start();
    return filter;
  }
}
