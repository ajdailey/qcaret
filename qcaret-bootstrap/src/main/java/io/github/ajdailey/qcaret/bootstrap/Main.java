package io.github.ajdailey.qcaret.bootstrap;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Entry point of {@code qcaret.jar}. Compiled for Java 8 so that an older runtime prints {@link
 * #versionMessage} and exits 2; on Java 21 and later it hands over to {@code Boot.start}.
 */
public final class Main {
  /** First Java feature release QCaret runs on. */
  static final int REQUIRED_FEATURE = 21;

  /** Exit status for an unsupported Java, platform or launch mode. */
  static final int EXIT_UNSUPPORTED = 2;

  private Main() {}

  /**
   * Starts QCaret, or exits with status 2 under a Java older than 21.
   *
   * @param args the command line, passed through unchanged
   * @throws Exception when startup fails with an error the bootstrap does not report itself
   */
  public static void main(String[] args) throws Exception {
    String spec = System.getProperty("java.specification.version");
    if (!supported(spec)) {
      System.err.println(versionMessage(System.getProperty("java.home"), spec));
      System.exit(EXIT_UNSUPPORTED);
    }
    // Reflection keeps this class free of a link to Boot, which is Java 21 bytecode.
    Method start =
        Class.forName("io.github.ajdailey.qcaret.bootstrap.Boot")
            .getMethod("start", String[].class);
    int status;
    try {
      status = ((Integer) start.invoke(null, (Object) args)).intValue();
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    }
    if (status != 0) {
      System.exit(status);
    }
  }

  /**
   * Reports whether a {@code java.specification.version} value is 21 or later.
   *
   * @param spec the property value, {@code 1.8} style or a feature number; null is unsupported
   * @return true for Java 21 and later
   */
  static boolean supported(String spec) {
    if (spec == null || spec.startsWith("1.")) {
      return false;
    }
    int end = 0;
    while (end < spec.length() && Character.isDigit(spec.charAt(end))) {
      end++;
    }
    if (end == 0 || end > 4) {
      return false;
    }
    return Integer.parseInt(spec.substring(0, end)) >= REQUIRED_FEATURE;
  }

  /**
   * Returns the one-line message for an unsupported Java.
   *
   * @param javaHome the running {@code java.home}
   * @param spec the running {@code java.specification.version}
   * @return the message, without a line terminator
   */
  static String versionMessage(String javaHome, String spec) {
    return "QCaret requires Java "
        + REQUIRED_FEATURE
        + " or later; "
        + javaHome
        + " is Java "
        + spec
        + ".";
  }
}
