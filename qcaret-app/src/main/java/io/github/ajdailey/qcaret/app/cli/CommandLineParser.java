package io.github.ajdailey.qcaret.app.cli;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses QCaret's command line: {@code --opt value}, {@code --opt=value}, {@code -o value},
 * combined short options ({@code -ml}, {@code -md2}), and {@code --} ending the options. Every
 * other argument is a file.
 *
 * <p>Paths are resolved against the working directory without touching the file system; the caller
 * validates them.
 */
public final class CommandLineParser {
  private final Path workingDirectory;

  /**
   * Creates a parser.
   *
   * @param workingDirectory resolves relative file and settings paths
   */
  public CommandLineParser(Path workingDirectory) {
    this.workingDirectory = workingDirectory;
  }

  /** The options; {@code value} marks one that takes a value. */
  private enum Option {
    HELP('h', "help", false),
    VERSION('v', "version", false),
    DEBUG('d', "debug", true),
    LOG('l', "log", false),
    MULTI('m', "multi", false),
    WINDOW_WIDTH('\0', "window-width", true),
    WINDOW_HEIGHT('\0', "window-height", true),
    WINDOW_X('\0', "window-x", true),
    WINDOW_Y('\0', "window-y", true),
    SETTINGS('\0', "settings", true);

    final char shortName;
    final String longName;
    final boolean value;

    Option(char shortName, String longName, boolean value) {
      this.shortName = shortName;
      this.longName = longName;
      this.value = value;
    }

    static Option byShort(char c) {
      for (Option option : values()) {
        if (option.shortName == c && c != '\0') {
          return option;
        }
      }
      return null;
    }

    static Option byLong(String name) {
      for (Option option : values()) {
        if (option.longName.equals(name)) {
          return option;
        }
      }
      return null;
    }
  }

  /** Mutable parse state. */
  private final class State {
    boolean help;
    boolean version;
    LogLevel level = LogLevel.WARN;
    boolean log;
    boolean multi;
    Integer width;
    Integer height;
    Integer x;
    Integer y;
    Path settings;
    final List<FileRequest> files = new ArrayList<FileRequest>();

    void apply(Option option, String value) throws CommandLineException {
      switch (option) {
        case HELP -> help = true;
        case VERSION -> version = true;
        case DEBUG -> level = debugLevel(value);
        case LOG -> log = true;
        case MULTI -> multi = true;
        case WINDOW_WIDTH -> width = size(option, value);
        case WINDOW_HEIGHT -> height = size(option, value);
        case WINDOW_X -> x = coordinate(option, value);
        case WINDOW_Y -> y = coordinate(option, value);
        case SETTINGS -> settings = path("--settings", value);
      }
    }
  }

  /**
   * Parses the arguments.
   *
   * @param args the command line, without the program name
   * @return the parsed command line
   * @throws CommandLineException on an unknown option or a missing or bad value
   */
  public CommandLine parse(String[] args) throws CommandLineException {
    State state = new State();
    boolean optionsEnded = false;
    int i = 0;
    while (i < args.length) {
      String arg = args[i];
      i++;
      if (!optionsEnded && arg.equals("--")) {
        optionsEnded = true;
      } else if (!optionsEnded && arg.startsWith("--")) {
        i = parseLong(state, arg, args, i);
      } else if (!optionsEnded && arg.startsWith("-") && arg.length() > 1) {
        i = parseShortGroup(state, arg, args, i);
      } else {
        state.files.add(new FileRequest(path(arg, arg)));
      }
    }
    StartupRequest request =
        new StartupRequest(
            state.files, state.width, state.height, state.x, state.y, state.settings, state.multi);
    CommandLine.Action action =
        state.help
            ? CommandLine.Action.HELP
            : state.version ? CommandLine.Action.VERSION : CommandLine.Action.RUN;
    return new CommandLine(action, request, state.level, state.log);
  }

  private int parseLong(State state, String arg, String[] args, int next)
      throws CommandLineException {
    int equals = arg.indexOf('=');
    String name = equals < 0 ? arg.substring(2) : arg.substring(2, equals);
    Option option = Option.byLong(name);
    if (option == null) {
      throw new CommandLineException("Unknown option --" + name);
    }
    if (!option.value) {
      if (equals >= 0) {
        throw new CommandLineException("--" + name + " takes no value");
      }
      state.apply(option, null);
      return next;
    }
    if (equals >= 0) {
      state.apply(option, arg.substring(equals + 1));
      return next;
    }
    if (next >= args.length) {
      throw new CommandLineException("--" + name + " needs a value");
    }
    state.apply(option, args[next]);
    return next + 1;
  }

  private int parseShortGroup(State state, String arg, String[] args, int next)
      throws CommandLineException {
    for (int j = 1; j < arg.length(); j++) {
      char c = arg.charAt(j);
      Option option = Option.byShort(c);
      if (option == null) {
        throw new CommandLineException("Unknown option -" + c);
      }
      if (!option.value) {
        state.apply(option, null);
        continue;
      }
      String rest = arg.substring(j + 1);
      if (!rest.isEmpty()) {
        state.apply(option, rest);
        return next;
      }
      if (next >= args.length) {
        throw new CommandLineException("-" + c + " needs a value");
      }
      state.apply(option, args[next]);
      return next + 1;
    }
    return next;
  }

  /** Maps a {@code -d} value: 0 WARN, 1 INFO, 2 DEBUG, 3 and above TRACE, or a level name. */
  private static LogLevel debugLevel(String value) throws CommandLineException {
    switch (value.strip()) {
      case "error":
        return LogLevel.ERROR;
      case "warn":
        return LogLevel.WARN;
      case "info":
        return LogLevel.INFO;
      case "debug":
        return LogLevel.DEBUG;
      case "trace":
        return LogLevel.TRACE;
      default:
        break;
    }
    long number = number("-d", value);
    if (number < 0) {
      throw new CommandLineException("-d: level must not be negative: " + value);
    }
    if (number == 0) {
      return LogLevel.WARN;
    }
    if (number == 1) {
      return LogLevel.INFO;
    }
    return number == 2 ? LogLevel.DEBUG : LogLevel.TRACE;
  }

  private static Integer size(Option option, String value) throws CommandLineException {
    long number = number("--" + option.longName, value);
    if (number < 1 || number > Integer.MAX_VALUE) {
      throw new CommandLineException("--" + option.longName + " must be a positive pixel count");
    }
    return Integer.valueOf((int) number);
  }

  private static Integer coordinate(Option option, String value) throws CommandLineException {
    long number = number("--" + option.longName, value);
    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
      throw new CommandLineException("--" + option.longName + " is out of range");
    }
    return Integer.valueOf((int) number);
  }

  private static long number(String option, String value) throws CommandLineException {
    String text = value.strip();
    if (text.isEmpty() || text.length() > 19) {
      throw new CommandLineException(option + " needs a number");
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      throw new CommandLineException(option + " needs a number");
    }
  }

  private Path path(String label, String value) throws CommandLineException {
    if (value.isEmpty() || value.indexOf('\0') >= 0) {
      throw new CommandLineException(label + " is not a valid path");
    }
    try {
      return workingDirectory.resolve(value);
    } catch (InvalidPathException e) {
      throw new CommandLineException(label + " is not a valid path");
    }
  }

  /**
   * Returns the usage text for {@code --help}.
   *
   * @return the text, lines separated by {@code \n}, ending with a line terminator
   */
  public static String usage() {
    return """
        Usage: qcaret [options] [file]...

          -h, --help               Print this help and exit
          -v, --version            Print the version and exit
          -d, --debug <level>      Log level: 1 info, 2 debug, 3 trace, or a level name
          -l, --log                Also write the log to a file
          -m, --multi              Run as an independent instance; window placement is not saved
              --window-width <px>  Initial client-area width
              --window-height <px> Initial client-area height
              --window-x <px>      Initial left edge of the window
              --window-y <px>      Initial top edge of the window
              --settings <dir>     Settings directory
          --                       End of options; the remaining arguments are files
        """;
  }
}
