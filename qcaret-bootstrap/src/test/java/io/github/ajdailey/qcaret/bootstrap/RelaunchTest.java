package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A relaunched child ends with its parent. SIGTERM to the parent stops the child before the parent
 * exits; SIGKILL closes the child's standard input, which ends it. On macOS the parent runs the
 * relaunch with {@code -XstartOnFirstThread}; elsewhere it runs the same child through {@link
 * MacRelaunch#run}. On Windows {@code Process.destroy} terminates the parent without running hooks,
 * so both cases take the standard input path.
 */
class RelaunchTest {
  private static final long END_SECONDS = 30;

  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @Timeout(120)
  void theChildEndsWithItsParent(boolean forcibly) throws Exception {
    String java = ProcessHandle.current().info().command().orElse(null);
    assumeTrue(java != null, "no java command");
    Target target = Target.current();
    String classPath = System.getProperty("java.class.path");
    List<String> command = new ArrayList<String>();
    command.add(java);
    command.add("-cp");
    command.add(classPath);
    command.add(RelaunchParentMain.class.getName());
    if (target != null && target.isMac()) {
      command.add(childJar().toString());
    } else {
      command.add(java);
      command.add("-cp");
      command.add(classPath);
      command.add(RelaunchChildMain.class.getName());
    }
    Process parent =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    ProcessHandle child = null;
    try (BufferedReader out =
        new BufferedReader(
            new InputStreamReader(parent.getInputStream(), StandardCharsets.UTF_8))) {
      String line = out.readLine();
      assertNotNull(line, "no child started");
      child = ProcessHandle.of(Long.parseLong(line.trim())).orElse(null);
      assertNotNull(child, "the child ended before its parent");
      if (forcibly) {
        parent.destroyForcibly();
      } else {
        parent.destroy();
      }
      assertTrue(parent.waitFor(END_SECONDS, TimeUnit.SECONDS), "the parent did not end");
      if (!forcibly && !(target != null && target.isWindows())) {
        assertFalse(child.isAlive(), "the parent ended before the child");
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(END_SECONDS);
      while (child.isAlive() && System.nanoTime() < deadline) {
        Thread.sleep(50);
      }
      assertFalse(child.isAlive(), "the child outlived its parent");
    } finally {
      if (child != null) {
        child.destroyForcibly();
      }
      parent.destroyForcibly();
      parent.waitFor(END_SECONDS, TimeUnit.SECONDS);
    }
  }

  /** A jar running {@link RelaunchChildMain} over this module's class directories. */
  private Path childJar() throws Exception {
    Manifest manifest = new Manifest();
    Attributes main = manifest.getMainAttributes();
    main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
    main.put(Attributes.Name.MAIN_CLASS, RelaunchChildMain.class.getName());
    main.put(
        Attributes.Name.CLASS_PATH, directory(MacRelaunch.class) + " " + directory(getClass()));
    Path jar = temp.resolve("child.jar");
    try (OutputStream file = Files.newOutputStream(jar);
        JarOutputStream out = new JarOutputStream(file, manifest)) {
      out.finish();
    }
    return jar;
  }

  private static String directory(Class<?> type) throws Exception {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
        .toUri()
        .toString();
  }
}
