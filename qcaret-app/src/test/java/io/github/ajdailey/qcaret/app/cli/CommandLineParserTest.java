package io.github.ajdailey.qcaret.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CommandLineParserTest {
  private static final Path CWD = Path.of("work").toAbsolutePath();
  private final CommandLineParser parser = new CommandLineParser(CWD);

  private CommandLine parse(String... args) throws CommandLineException {
    return parser.parse(args);
  }

  @Test
  void multiInstanceWithAFile() throws Exception {
    Path file = CWD.getRoot().resolve("srv").resolve("notes.txt");
    CommandLine line = parse("-m", file.toString());
    assertEquals(CommandLine.Action.RUN, line.action());
    assertTrue(line.request().multi());
    assertEquals(List.of(new FileRequest(file)), line.request().files());
  }

  @Test
  void combinedShortOptions() throws Exception {
    CommandLine line = parse("-ml", "a.txt");
    assertTrue(line.request().multi());
    assertTrue(line.logToFile());
    assertEquals(CWD.resolve("a.txt"), line.request().files().get(0).path());
  }

  @ParameterizedTest
  @CsvSource({
    "--window-width=900, --window-height=600",
    "--window-width, 900",
  })
  void longOptionValueForms(String first, String second) throws Exception {
    CommandLine line =
        first.contains("=") ? parse(first, second) : parse(first, second, "--window-height", "600");
    assertEquals(900, line.request().width());
    assertEquals(600, line.request().height());
    assertNull(line.request().x());
    assertTrue(line.request().placesWindow());
  }

  @Test
  void windowPosition() throws Exception {
    CommandLine line = parse("--window-x", "-50", "--window-y=40");
    assertEquals(-50, line.request().x());
    assertEquals(40, line.request().y());
    assertTrue(line.request().placesWindow());
    assertFalse(parse("a.txt").request().placesWindow());
  }

  @ParameterizedTest
  @CsvSource({
    "-d, 1, INFO",
    "-d, 2, DEBUG",
    "-d, 3, TRACE",
    "-d, 9, TRACE",
    "-d, 0, WARN",
    "--debug, error, ERROR",
    "--debug, trace, TRACE"
  })
  void debugLevels(String option, String value, LogLevel level) throws Exception {
    assertEquals(level, parse(option, value).logLevel());
  }

  @Test
  void debugValueInGroup() throws Exception {
    CommandLine line = parse("-md2");
    assertTrue(line.request().multi());
    assertEquals(LogLevel.DEBUG, line.logLevel());
    assertEquals(LogLevel.WARN, parse().logLevel());
  }

  @Test
  void optionAfterDoubleDashIsAFile() throws Exception {
    assertEquals(CWD.resolve("-m"), parse("--", "-m").request().files().get(0).path());
    assertFalse(parse("--", "-m").request().multi());
  }

  @Test
  void plusArgumentsAreFiles() throws Exception {
    assertEquals(CWD.resolve("+x.txt"), parse("+x.txt").request().files().get(0).path());
  }

  @Test
  void settingsDirectory() throws Exception {
    assertEquals(CWD.resolve("cfg"), parse("--settings", "cfg").request().settingsDirectory());
    assertNull(parse().request().settingsDirectory());
  }

  @Test
  void helpAndVersion() throws Exception {
    assertEquals(CommandLine.Action.HELP, parse("-h").action());
    assertEquals(CommandLine.Action.HELP, parse("--help").action());
    assertEquals(CommandLine.Action.VERSION, parse("-v").action());
    assertTrue(parse("-l").logToFile());
    assertFalse(parse().logToFile());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "--bogus",
        "-x",
        "--window-width",
        "--window-width=0",
        "--window-width=wide",
        "--window-height=-3",
        "--window-x=99999999999",
        "--multi=yes",
        "--settings=",
        "-d",
        "-d=-1",
        "-mz"
      })
  void rejects(final String arg) {
    assertThrows(
        CommandLineException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            parse(arg);
          }
        });
  }

  @Test
  void usageListsEveryOption() {
    String usage = CommandLineParser.usage();
    for (String option :
        List.of(
            "--help",
            "--version",
            "--debug",
            "--log",
            "--multi",
            "--window-width",
            "--window-height",
            "--window-x",
            "--window-y",
            "--settings")) {
      assertTrue(usage.contains(option), option);
    }
  }
}
