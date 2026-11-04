package io.github.ajdailey.qcaret.app.cli;

/**
 * A parsed command line.
 *
 * @param action what to do
 * @param request the startup request; meaningful for {@link Action#RUN}
 * @param logLevel {@code -d}, {@link LogLevel#WARN} when absent
 * @param logToFile {@code -l}
 */
public record CommandLine(
    Action action, StartupRequest request, LogLevel logLevel, boolean logToFile) {
  /** What the command line asks for. */
  public enum Action {
    /** Open the window. */
    RUN,
    /** {@code -h} or {@code --help}: print usage, exit 0. */
    HELP,
    /** {@code -v} or {@code --version}: print the version, exit 0. */
    VERSION
  }
}
