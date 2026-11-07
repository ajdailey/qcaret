package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A real input method through the real GTK 3 path: {@link IBusSession} runs {@code ibus-daemon} and
 * {@link IBusDriver} sends X key events at a real window, one parameter per check. Skips on a host
 * that cannot run it: Windows, macOS, GTK 4, and Linux without a working daemon, engine or {@code
 * xdotool}. With {@code -Dqcaret.ibus.required=true} each of those skips on Linux fails.
 */
@Tag("swt")
class IBusCompositionTest {
  private static final Map<String, String> ROWS = new HashMap<String, String>();

  @BeforeAll
  static void run(@TempDir Path temp) throws Exception {
    boolean linux = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("linux");
    assumeTrue(linux, "IBus runs on Linux only");
    if ("1".equals(System.getenv("SWT_GTK4"))) {
      unavailable("GTK 4");
    }
    String missing = IBusSession.missingTool();
    if (missing != null) {
      unavailable(missing + " is not on PATH");
    }
    Path logs = Path.of("target", "diagnostics", "ibus");
    Path results = logs.resolve("results.txt").toAbsolutePath();
    try (IBusSession session = new IBusSession(temp, logs)) {
      session.start();
      session.drive(results);
    } catch (IBusSession.Unavailable e) {
      unavailable(e.getMessage());
    }
    read(results);
  }

  private static void read(Path results) throws IOException {
    if (!Files.exists(results)) {
      unavailable("the driver wrote no results; see driver.log");
    }
    boolean ready = false;
    for (String line : Files.readAllLines(results, StandardCharsets.UTF_8)) {
      if (line.startsWith(IBusDriver.UNAVAILABLE)) {
        unavailable(line);
      } else if (line.equals(IBusDriver.READY)) {
        ready = true;
      } else if (line.startsWith(IBusDriver.ROW)) {
        String[] fields = line.split(" ", 3);
        ROWS.put(fields[1], fields[2]);
      }
    }
    if (!ready) {
      unavailable("the driver stopped before a composition; see driver.log");
    }
  }

  private static void unavailable(String reason) {
    if (Boolean.getBoolean("qcaret.ibus.required")) {
      fail("IBus required: " + reason);
    }
    assumeTrue(false, reason);
  }

  static List<String> rows() {
    return IBusDriver.rows();
  }

  @ParameterizedTest
  @MethodSource("rows")
  void row(String name) {
    assertEquals(IBusDriver.PASS, ROWS.get(name), name);
  }
}
