package io.github.ajdailey.qcaret.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

  @Test
  void aPositionGoesToTheNextFileOnly() throws Exception {
    List<FileRequest> files = parse("+12:3", "a.txt", "b.txt", "+7", "c.txt").request().files();
    assertEquals(new FileRequest(CWD.resolve("a.txt"), 12, 3, false, null), files.get(0));
    assertEquals(new FileRequest(CWD.resolve("b.txt")), files.get(1));
    assertEquals(new FileRequest(CWD.resolve("c.txt"), 7, 0, false, null), files.get(2));
  }

  @Test
  void readOnlyAndEncodingApplyToTheFollowingFiles() throws Exception {
    List<FileRequest> files =
        parse("a.txt", "-r", "b.txt", "-e", "iso-8859-1", "c.txt", "d.txt").request().files();
    assertEquals(new FileRequest(CWD.resolve("a.txt")), files.get(0));
    assertEquals(new FileRequest(CWD.resolve("b.txt"), 0, 0, true, null), files.get(1));
    assertEquals(new FileRequest(CWD.resolve("c.txt"), 0, 0, true, "ISO-8859-1"), files.get(2));
    assertEquals(new FileRequest(CWD.resolve("d.txt"), 0, 0, true, "ISO-8859-1"), files.get(3));
  }

  @Test
  void readOnlyCombinesWithOtherShortOptions() throws Exception {
    StartupRequest request = parse("-nre", "UTF-8", "a.txt").request();
    assertTrue(request.newWindow());
    assertEquals(
        new FileRequest(CWD.resolve("a.txt"), 0, 0, true, "UTF-8"), request.files().get(0));
  }

  @Test
  void afterTheEndOfOptionsAPositionIsAFile() throws Exception {
    List<FileRequest> files = parse("+3", "--", "+4").request().files();
    assertEquals(new FileRequest(CWD.resolve("+4"), 3, 0, false, null), files.get(0));
    assertEquals(1, files.size());
  }

  @Test
  void aPlusNameThatIsNoPositionIsAFile() throws Exception {
    assertEquals(CWD.resolve("+notes"), parse("+notes").request().files().get(0).path());
  }

  @Test
  @DisabledOnOs(OS.WINDOWS)
  void aNameWithAColonOrAMalformedPositionIsAFile() throws Exception {
    assertEquals(CWD.resolve(":x"), parse(":x").request().files().get(0).path());
    assertEquals(CWD.resolve("+1:"), parse("+1:").request().files().get(0).path());
    assertEquals(CWD.resolve("+:2"), parse("+:2").request().files().get(0).path());
    assertEquals(CWD.resolve("+"), parse("+").request().files().get(0).path());
  }

  @Test
  void aPlusAloneIsAFile() throws Exception {
    assertEquals(CWD.resolve("+"), parse("+").request().files().get(0).path());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void aNameWithAColonIsAUsageErrorOnWindows() {
    assertThrows(
        CommandLineException.class,
        new Executable() {
          @Override
          public void execute() throws Exception {
            parse(":x");
          }
        });
  }

  @Test
  void anEmptyArgumentIsRefused() {
    assertThrows(
        CommandLineException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            parse("");
          }
        });
  }

  @Test
  void sessionOptionsSetTheMode() throws Exception {
    assertEquals(SessionMode.DEFAULT, parse().request().session());
    assertTrue(parse().request().restoresSession());
    assertFalse(parse("a.txt").request().restoresSession());
    assertTrue(parse("--session", "a.txt").request().restoresSession());
    assertFalse(parse("--no-session").request().restoresSession());
    assertFalse(parse("--diff", "a", "b").request().restoresSession());
  }

  @Test
  void diffTakesTwoFiles() throws Exception {
    StartupRequest request = parse("--diff", "a.txt", "b.txt", "c.txt").request();
    assertEquals(CWD.resolve("a.txt"), request.diffLeft());
    assertEquals(CWD.resolve("b.txt"), request.diffRight());
    assertEquals(List.of(new FileRequest(CWD.resolve("c.txt"))), request.files());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "+0 a.txt",
        "+1:0 a.txt",
        "+99999999999 a.txt",
        "+5",
        "--diff a.txt",
        "--diff=a.txt b.txt",
        "-e no-such-charset a.txt",
        "-e",
        "-m -n",
        "--session=1"
      })
  void refusedLines(final String line) {
    assertThrows(
        CommandLineException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            parse(line.split(" "));
          }
        });
  }

  @Test
  void theUsageNamesEveryOption() {
    String usage = CommandLineParser.usage();
    for (String option :
        List.of(
            "--new-window", "--read-only", "--encoding", "--session", "--no-session", "--diff")) {
      assertTrue(usage.contains(option), option);
    }
  }
}
