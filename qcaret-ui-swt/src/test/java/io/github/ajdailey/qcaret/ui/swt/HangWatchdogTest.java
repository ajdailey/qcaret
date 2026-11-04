package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HangWatchdogTest {
  @Test
  void theDumpNamesTheTestAndShowsTheCallingThreadsStack() {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    HangWatchdog.dump(out, "[method:mock()]", 7);
    String text = bytes.toString(StandardCharsets.UTF_8);
    assertTrue(text.startsWith("HANG: [method:mock()] still running after 7 s"), text);
    assertTrue(text.contains('"' + Thread.currentThread().getName() + '"'), text);
    assertTrue(text.contains("theDumpNamesTheTestAndShowsTheCallingThreadsStack"), text);
  }
}
