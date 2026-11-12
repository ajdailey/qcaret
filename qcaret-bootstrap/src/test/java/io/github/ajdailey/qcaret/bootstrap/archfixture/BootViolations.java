package io.github.ajdailey.qcaret.bootstrap.archfixture;

import java.io.IOException;
import javax.naming.Context;

/** Bootstrap classes that each break one architecture rule; never instantiated. */
final class BootViolations {
  private BootViolations() {}

  /** Uses a JDK class outside {@code java..}. */
  static final class UsesJavax {
    String name(Context context) {
      return context.toString();
    }
  }

  /** Starts processes. */
  static final class StartsProcesses {
    void start() throws IOException {
      new ProcessBuilder("mock");
      Runtime.getRuntime().exec("mock");
    }
  }

  /** Reads the OS name outside {@code Target}. */
  static final class ReadsOsName {
    String os() {
      return System.getProperty("os.name");
    }
  }

  /** Ends the JVM. */
  static final class Exits {
    void exit() {
      System.exit(1);
    }
  }
}
