package io.github.ajdailey.qcaret.launcher;

/**
 * Application entry point, loaded by the bootstrap through its nested-jar class loader. Configures
 * logging ({@link LogConfigurator#install}) before any class logs. Calls {@code System.exit} only
 * for a nonzero status, which {@link Launcher#run} returns only before the Display exists; after a
 * normal close {@code main} returns and the JVM exits on its own.
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
    int status = new Launcher(System.out, System.err).run(args);
    if (status != 0) {
      System.exit(status);
    }
  }
}
