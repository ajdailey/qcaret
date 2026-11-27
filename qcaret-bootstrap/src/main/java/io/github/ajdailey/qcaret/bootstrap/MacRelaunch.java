package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Restarts the JVM with {@code -XstartOnFirstThread} on macOS, where SWT must create its Display on
 * the process main thread. The JDK launcher sets {@code JAVA_STARTED_ON_FIRST_THREAD_<pid>=1} when
 * it runs with the flag; its absence means a relaunch is needed.
 *
 * <p>The child runs this JVM's own binary with a fixed argument list plus the original arguments as
 * separate elements; no shell is involved. The child carries {@code -Dqcaret.relaunched=true} and
 * never relaunches again. JVM options given to the first process are not carried over.
 *
 * <p>The child ends with the parent. SIGTERM or SIGINT to the parent runs its shutdown hook, which
 * sends the child SIGTERM and waits for it. The child's standard input is a pipe the parent holds
 * and never writes, so any other end of the parent, SIGKILL included, closes it, and the child's
 * {@link #watchParent} exits at end of file.
 */
final class MacRelaunch {
  static final String RELAUNCHED = "qcaret.relaunched";

  /** Exit status of a child whose parent has ended: the status SIGTERM gives. */
  static final int EXIT_PARENT_ENDED = 143;

  /** Seconds the parent's shutdown hook waits for the child. */
  private static final long STOP_SECONDS = 5;

  private MacRelaunch() {}

  /** Outcome of {@link #decide}. */
  enum Decision {
    /** Already on the main thread, or not macOS. */
    PROCEED,
    /** Relaunch with the flag. */
    RELAUNCH,
    /** A relaunched process is still off the main thread. */
    FAIL
  }

  /**
   * Decides whether this process must relaunch.
   *
   * @param target the running target
   * @param env the process environment
   * @param pid this process's id
   * @param relaunched the value of the {@code qcaret.relaunched} system property
   * @return the decision
   */
  static Decision decide(Target target, Map<String, String> env, long pid, String relaunched) {
    if (!target.isMac() || "1".equals(env.get("JAVA_STARTED_ON_FIRST_THREAD_" + pid))) {
      return Decision.PROCEED;
    }
    return "true".equals(relaunched) ? Decision.FAIL : Decision.RELAUNCH;
  }

  /**
   * Builds the child command.
   *
   * @param java the JVM binary
   * @param jar the universal jar
   * @param args the original arguments
   * @return the command, one element per argument
   */
  static List<String> command(String java, Path jar, String[] args) {
    List<String> command = new ArrayList<String>();
    command.add(java);
    command.add("-XstartOnFirstThread");
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-D" + RELAUNCHED + "=true");
    command.add("-jar");
    command.add(jar.toString());
    for (String arg : args) {
      command.add(arg);
    }
    return command;
  }

  /**
   * Runs the child and waits for it.
   *
   * @param jar the universal jar
   * @param args the original arguments
   * @return the child's exit status
   * @throws IOException when the child cannot start
   * @throws InterruptedException when interrupted while waiting
   */
  static int relaunch(Path jar, String[] args) throws IOException, InterruptedException {
    String java =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    return run(command(java, jar, args));
  }

  /**
   * Runs a child with inherited standard output and error and a standard input pipe this process
   * holds open, and waits for it. Until the child ends, a shutdown hook sends it SIGTERM and waits
   * up to five seconds for it.
   *
   * @param command the child command
   * @return the child's exit status
   * @throws IOException when the child cannot start
   * @throws InterruptedException when interrupted while waiting
   */
  static int run(List<String> command) throws IOException, InterruptedException {
    final AtomicReference<Process> started = new AtomicReference<Process>();
    Thread stop =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                Process child = started.get();
                if (child == null) {
                  return;
                }
                child.destroy();
                try {
                  child.waitFor(STOP_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            "qcaret-relaunch-stop");
    // Registered before the child starts, so no signal falls between the start and the hook.
    Runtime.getRuntime().addShutdownHook(stop);
    try {
      Process child =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.INHERIT)
              .redirectError(ProcessBuilder.Redirect.INHERIT)
              .start();
      started.set(child);
      return child.waitFor();
    } finally {
      try {
        Runtime.getRuntime().removeShutdownHook(stop);
      } catch (IllegalStateException e) {
        // Shutdown is running the hook.
      }
    }
  }

  /**
   * Exits with {@link #EXIT_PARENT_ENDED}, running the shutdown hooks, when a relaunched child's
   * standard input reaches end of file. Reads on a daemon thread; a read error ends the watch and
   * leaves the process running.
   *
   * @param in the standard input
   */
  static void watchParent(final InputStream in) {
    Thread watch =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try {
                  while (in.read() != -1) {
                    // The parent writes nothing; discard anything else.
                  }
                } catch (IOException e) {
                  return;
                }
                Runtime.getRuntime().exit(EXIT_PARENT_ENDED);
              }
            },
            "qcaret-parent-watch");
    watch.setDaemon(true);
    watch.start();
  }
}
