package io.github.ajdailey.qcaret.dist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code install-desktop-entry} from install trees whose path holds characters the Desktop
 * Entry Exec key escapes, and decodes the written Exec line back to the launcher path. POSIX only.
 */
class DesktopEntryTest {
  private static final long BOUND_SECONDS = 60;
  private static final List<String> FOLDERS =
      List.of(
          "plain",
          "with space",
          "100%",
          "back\\slash",
          "quote\"d",
          "dollar$HOME",
          "back`tick`",
          "R&D (x)",
          "pipe|and;semi'single",
          "all %F \\\\ \"$`&|'(");

  @TempDir Path temp;

  @Test
  void execNamesTheLauncherForEveryPath() throws Exception {
    assumeTrue(File.separatorChar == '/', "POSIX script");
    String validator = onPath("desktop-file-validate");
    for (int i = 0; i < FOLDERS.size(); i++) {
      String folder = FOLDERS.get(i);
      Path home = Files.createDirectories(temp.resolve(Integer.toString(i)).resolve(folder));
      Path bin = Files.createDirectory(home.resolve("bin"));
      Path share = Files.createDirectory(home.resolve("share"));
      Path script =
          Files.copy(
              Path.of("src", "linux", "install-desktop-entry"),
              bin.resolve("install-desktop-entry"));
      Files.copy(Path.of("src", "linux", "qcaret.desktop"), share.resolve("qcaret.desktop"));
      Files.copy(Path.of("src", "icons", "qcaret.svg"), share.resolve("qcaret.svg"));
      Path data = temp.resolve(i + "-data");

      Result install = run(List.of("sh", script.toString()), data);
      assertEquals(0, install.exit(), folder + ": " + install.output());
      Path entry = data.resolve("applications").resolve("qcaret.desktop");
      String exec = null;
      for (String line : Files.readAllLines(entry, StandardCharsets.UTF_8)) {
        if (line.startsWith("Exec=")) {
          exec = line.substring("Exec=".length());
        }
      }
      assertEquals(
          List.of(home.toRealPath().resolve("bin").resolve("qcaret").toString(), "%F"),
          execArguments(exec),
          folder + ": " + exec);
      if (validator != null) {
        Result report = run(List.of(validator, entry.toString()), data);
        assertEquals(0, report.exit(), folder + ": " + report.output());
      }

      assertEquals(0, run(List.of("sh", script.toString(), "--uninstall"), data).exit(), folder);
      assertTrue(Files.notExists(entry), folder);
    }
  }

  private record Result(int exit, String output) {}

  private static Result run(List<String> command, Path data)
      throws IOException, InterruptedException {
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("XDG_DATA_HOME", data.toString());
    Process process = builder.start();
    String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS), command.toString());
    return new Result(process.exitValue(), text);
  }

  private static String onPath(String program) {
    String path = System.getenv("PATH");
    if (path == null) {
      return null;
    }
    for (String dir : path.split(File.pathSeparator)) {
      Path candidate = Path.of(dir).resolve(program);
      if (!dir.isEmpty() && Files.isExecutable(candidate)) {
        return candidate.toString();
      }
    }
    return null;
  }

  /**
   * Decodes an Exec value as the Desktop Entry specification defines it: string escapes first, then
   * the argument quoting and {@code %%}. A field code is kept as its own argument.
   */
  static List<String> execArguments(String value) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '\\') {
        i++;
        char e = value.charAt(i);
        switch (e) {
          case 's' -> text.append(' ');
          case 'n' -> text.append('\n');
          case 't' -> text.append('\t');
          case 'r' -> text.append('\r');
          case '\\' -> text.append('\\');
          default -> throw new IllegalArgumentException("string escape \\" + e + " in " + value);
        }
      } else {
        text.append(c);
      }
    }
    List<String> args = new ArrayList<String>();
    StringBuilder arg = null;
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          quoted = false;
        } else if (c == '\\') {
          i++;
          char e = text.charAt(i);
          if ("\"`$\\".indexOf(e) < 0) {
            throw new IllegalArgumentException("quoted escape \\" + e + " in " + text);
          }
          arg.append(e);
        } else if (c == '%') {
          i++;
          if (text.charAt(i) != '%') {
            throw new IllegalArgumentException("field code inside quotes in " + text);
          }
          arg.append('%');
        } else {
          arg.append(c);
        }
      } else if (c == ' ') {
        if (arg != null) {
          args.add(arg.toString());
          arg = null;
        }
      } else {
        if (arg == null) {
          arg = new StringBuilder();
        }
        if (c == '"') {
          quoted = true;
        } else if ("\\`$&|;'()<>~*?#".indexOf(c) >= 0) {
          throw new IllegalArgumentException("reserved " + c + " outside quotes in " + text);
        } else {
          arg.append(c);
        }
      }
    }
    if (quoted) {
      throw new IllegalArgumentException("unclosed quote in " + text);
    }
    if (arg != null) {
      args.add(arg.toString());
    }
    return args;
  }
}
