package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Restarts the JVM with {@code -XstartOnFirstThread} on macOS, where SWT must create its Display on
 * the process main thread. The JDK launcher sets {@code JAVA_STARTED_ON_FIRST_THREAD_<pid>=1} when
 * it runs with the flag; its absence means a relaunch is needed.
 *
 * <p>The child runs this JVM's own binary with a fixed argument list plus the original arguments as
 * separate elements; no shell is involved. The child carries {@code -Dqcaret.relaunched=true} and
 * never relaunches again. JVM options given to the first process are not carried over.
 */
final class MacRelaunch {
  static final String RELAUNCHED = "qcaret.relaunched";

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
    command.add("-D" + RELAUNCHED + "=true");
    command.add("-jar");
    command.add(jar.toString());
    for (String arg : args) {
      command.add(arg);
    }
    return command;
  }

  /**
   * Runs the child with inherited standard streams and waits for it.
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
    Process child = new ProcessBuilder(command(java, jar, args)).inheritIO().start();
    return child.waitFor();
  }
}
