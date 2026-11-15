package io.github.ajdailey.qcaret.bootstrap;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * A relaunch parent for {@link RelaunchTest}: one argument relaunches that jar as macOS does, more
 * run them as the child command.
 */
public final class RelaunchParentMain {
  private RelaunchParentMain() {}

  /**
   * Runs the child and exits with its status.
   *
   * @param args a jar, or the child command
   * @throws Exception when the child cannot start
   */
  public static void main(String[] args) throws Exception {
    int status =
        args.length == 1
            ? MacRelaunch.relaunch(Path.of(args[0]), new String[0])
            : MacRelaunch.run(Arrays.asList(args));
    System.exit(status);
  }
}
