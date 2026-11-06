package io.github.ajdailey.qcaret.app.ipc;

import java.io.IOException;
import java.nio.file.Path;

/** A child process for {@code HandoffTest}: claims the instance lock, says so, and waits to die. */
public final class ClaimMain {
  static final String READY = "claimed";

  private ClaimMain() {}

  /**
   * Claims the lock in the given directory and blocks.
   *
   * @param args the settings directory
   * @throws IOException when the claim fails
   * @throws InterruptedException never in practice; the test kills the process
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    InstanceServer server = InstanceServer.claim(Path.of(args[0]));
    System.out.println(server == null ? "busy" : READY);
    System.out.flush();
    Thread.sleep(Long.MAX_VALUE);
  }
}
