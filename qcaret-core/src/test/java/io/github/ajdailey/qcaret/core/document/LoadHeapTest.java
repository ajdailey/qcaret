package io.github.ajdailey.qcaret.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loads at the budget's limit in a child JVM with a small heap. */
class LoadHeapTest {
  @TempDir Path temp;

  private String probe(String kind) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx128m",
                "-cp",
                System.getProperty("java.class.path"),
                LoadHeapProbe.class.getName(),
                temp.resolve(kind + ".txt").toString(),
                kind)
            .redirectErrorStream(true)
            .start();
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
    assertTrue(process.waitFor(120, TimeUnit.SECONDS), kind);
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

  /** An ASCII head admits the file at twice what its text costs: refused, not out of memory. */
  @Test
  void wideTextBehindAnAsciiHeadIsRefusedInMidLoad() throws Exception {
    assertEquals("REFUSED", probe("late"));
  }
}
