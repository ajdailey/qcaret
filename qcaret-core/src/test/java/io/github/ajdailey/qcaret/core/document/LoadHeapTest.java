package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads at the budget's limit in a child JVM with a small heap. */
class LoadHeapTest {
  @TempDir Path temp;

  private String probe(String... kind) throws IOException, InterruptedException {
    List<String> command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx128m");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(LoadHeapProbe.class.getName());
    command.add(temp.resolve(kind[0] + ".txt").toString());
    command.addAll(List.of(kind));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String last = "";
    try (BufferedReader out =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line = out.readLine();
      while (line != null) {
        last = line;
        line = out.readLine();
      }
    }
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), kind[0]);
    assertEquals(0, process.exitValue(), last);
    return last;
  }

  @Test
  void asciiTextAtTheLimitLoads() throws Exception {
    assertTrue(probe("ascii").startsWith("LOADED "));
  }

  @Test
  void wideTextAtTheLimitLoads() throws Exception {
    assertTrue(probe("wide").startsWith("LOADED "));
  }

  /** Two copies of the text pass the heap here, so these hold only the changed span. */
  @Test
  void asciiTextAtTheLimitReloadsAnEdit() throws Exception {
    assertEquals("RELOADED 1 0", probe("ascii", "edit"));
  }

  @Test
  void wideTextAtTheLimitReloadsAnEdit() throws Exception {
    assertEquals("RELOADED 1 0", probe("wide", "edit"));
  }

  @Test
  void asciiTextAtTheLimitReloadsAnAddedLine() throws Exception {
    assertEquals("RELOADED 1 " + LoadHeapProbe.ADDED_CHARS, probe("ascii", "append"));
  }

  @Test
  void wideTextAtTheLimitReloadsAnAddedLine() throws Exception {
    assertEquals("RELOADED 1 " + LoadHeapProbe.ADDED_CHARS, probe("wide", "append"));
  }

  @Test
  void aRewriteAtTheLimitIsRefusedNotOutOfMemory() throws Exception {
    assertEquals("RELOAD REFUSED", probe("ascii", "rewrite"));
  }

  /** An ASCII head admits the file at twice what its text costs: refused, not out of memory. */
  @Test
  void wideTextBehindAnAsciiHeadIsRefusedInMidLoad() throws Exception {
    assertEquals("REFUSED", probe("late"));
  }
}
