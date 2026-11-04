package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MainTest {
  @Test
  void acceptsJava21AndLater() {
    assertTrue(Main.supported("21"));
    assertTrue(Main.supported("25"));
    assertFalse(Main.supported("17"));
    assertFalse(Main.supported("1.8"));
    assertFalse(Main.supported(null));
    assertFalse(Main.supported("x"));
  }

  @Test
  void messageNamesTheRunningJava() {
    assertEquals(
        "QCaret requires Java 21 or later; /opt/jdk8 is Java 1.8.",
        Main.versionMessage("/opt/jdk8", "1.8"));
  }

  /** Main must load on Java 8 to print its message, so its class file is version 52. */
  @Test
  void mainIsJava8Bytecode() throws IOException {
    try (InputStream in = Main.class.getResourceAsStream("Main.class");
        DataInputStream data = new DataInputStream(in)) {
      assertEquals(0xCAFEBABE, data.readInt());
      data.readUnsignedShort();
      assertEquals(52, data.readUnsignedShort());
    }
  }

  /**
   * Runs Main under the Java 8 named by the qcaret.test.java8 system property (a java binary);
   * skipped when it is not set.
   */
  @Test
  void java8PrintsMessageAndExits2() throws Exception {
    String java8 = System.getProperty("qcaret.test.java8", "");
    assumeTrue(!java8.isEmpty() && Files.isExecutable(Path.of(java8)), "no Java 8 configured");
    Path classes = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    List<String> command = new ArrayList<String>();
    command.add(java8);
    command.add("-cp");
    command.add(classes.toString());
    command.add("io.github.ajdailey.qcaret.bootstrap.Main");
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output;
    try (InputStream in = process.getInputStream()) {
      output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(process.waitFor(60, TimeUnit.SECONDS));
    assertEquals(2, process.exitValue());
    assertTrue(
        output.startsWith("QCaret requires Java 21 or later; ") && output.contains(" is Java 1.8."),
        output);
  }
}
