package io.github.ajdailey.qcaret.dist;

import io.github.ajdailey.qcaret.bootstrap.CmdArguments;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for {@code qcaret.jar} under a launcher. Writes {@code home=<java.home>}, {@code
 * jar=<java.class.path>}, one {@code opt=} line per JVM option and one {@code arg=} line per
 * argument as the bootstrap reads them ({@link CmdArguments}), in UTF-8, to the file {@code
 * QCARET_PROBE_OUT} names.
 */
public final class LauncherProbe {
  private LauncherProbe() {}

  /**
   * Records the launch.
   *
   * @param args the arguments the launcher passed
   * @throws IOException when the file cannot be written
   */
  public static void main(String[] args) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add("home=" + System.getProperty("java.home"));
    lines.add("jar=" + System.getProperty("java.class.path"));
    for (String option : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
      lines.add("opt=" + option);
    }
    String launcher = System.getProperty(CmdArguments.LAUNCHER);
    for (String arg : CmdArguments.of(args, launcher, System.getenv())) {
      lines.add("arg=" + arg);
    }
    Path out = Path.of(System.getenv("QCARET_PROBE_OUT"));
    Path part = out.resolveSibling(out.getFileName() + ".part");
    Files.write(part, lines, StandardCharsets.UTF_8);
    Files.move(part, out);
  }
}
