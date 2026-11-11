package io.github.ajdailey.qcaret.app.session;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A child process that tries {@link SessionLock} in a settings directory, prints {@link #HOLDING}
 * or {@link #BUSY}, and holds the lock until its standard input ends or the test kills it.
 */
public final class SessionLockMain {
  /** Printed when the lock was taken. */
  public static final String HOLDING = "holding";

  /** Printed when another holder kept it. */
  public static final String BUSY = "busy";

  private SessionLockMain() {}

  /**
   * Tries the lock.
   *
   * @param args the settings directory
   * @throws IOException when the lock fails
   */
  public static void main(String[] args) throws IOException {
    SessionLock lock =
        SessionLock.acquire(new SettingsLocation(SettingsMode.EXPLICIT, Path.of(args[0])));
    System.out.println(lock == null ? BUSY : HOLDING);
    System.out.flush();
    BufferedReader in =
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    String line = in.readLine();
    while (line != null) {
      line = in.readLine();
    }
    if (lock != null) {
      lock.close();
    }
  }

  /** One child with its first line read; {@link #close} kills it. */
  public static final class Child implements AutoCloseable {
    private static final long BOUND_SECONDS = 60;

    private final Process process;
    private final String said;

    /**
     * Starts a child on a settings directory and reads its first line.
     *
     * @param settings the settings directory
     * @throws IOException when the child cannot start
     */
    public Child(Path settings) throws IOException {
      process =
          new ProcessBuilder(
                  List.of(
                      Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                      "--enable-native-access=ALL-UNNAMED",
                      "-cp",
                      System.getProperty("java.class.path"),
                      SessionLockMain.class.getName(),
                      settings.toString()))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      BufferedReader out =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      said = out.readLine();
    }

    /**
     * Returns the child's first line.
     *
     * @return {@link #HOLDING}, {@link #BUSY}, or null when the child ended first
     */
    public String said() {
      return said;
    }

    @Override
    public void close() throws IOException, InterruptedException {
      process.destroyForcibly();
      try {
        if (!process.waitFor(BOUND_SECONDS, TimeUnit.SECONDS)) {
          throw new IOException("The child did not end");
        }
      } finally {
        process.getInputStream().close();
        process.getOutputStream().close();
      }
    }
  }
}
