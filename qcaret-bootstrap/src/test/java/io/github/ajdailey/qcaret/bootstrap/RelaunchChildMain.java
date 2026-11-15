package io.github.ajdailey.qcaret.bootstrap;

/**
 * A relaunched child for {@link RelaunchTest}: watches its parent, prints its process id and waits
 * at most a minute.
 */
public final class RelaunchChildMain {
  private static final long WAIT_MILLIS = 60_000;

  private RelaunchChildMain() {}

  /**
   * Runs the child.
   *
   * @param args ignored
   * @throws InterruptedException when interrupted while waiting
   */
  public static void main(String[] args) throws InterruptedException {
    MacRelaunch.watchParent(System.in);
    System.out.println(ProcessHandle.current().pid());
    System.out.flush();
    Thread.sleep(WAIT_MILLIS);
  }
}
