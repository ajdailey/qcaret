package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.WildcardExpansion;
import java.util.List;

/**
 * Application entry point, loaded by the bootstrap through its nested-jar class loader. Configures
 * logging ({@link LogConfigurator#install}) before any class logs. Calls {@code System.exit} only
 * for the nonzero status {@link Launcher#run} returns; after a normal close {@code main} returns
 * and the JVM exits on its own.
 */
public final class Main {
  private Main() {}

  /**
   * Runs QCaret.
   *
   * @param args the command line
   */
  public static void main(String[] args) {
    LogConfigurator.install();
    List<String> expanded =
        WildcardExpansion.patterns(
            args, System.getProperty("sun.java.command"), System.getProperty("java.class.path"));
    int status = new Launcher(System.out, System.err).run(args, expanded);
    if (status != 0) {
      System.exit(status);
    }
  }
}
