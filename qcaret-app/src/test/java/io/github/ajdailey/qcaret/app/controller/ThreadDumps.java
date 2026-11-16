package io.github.ajdailey.qcaret.app.controller;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Thread dumps that include virtual threads, which {@code Thread.getAllStackTraces} omits. */
final class ThreadDumps {
  private ThreadDumps() {}

  /**
   * Dumps every thread as plain text.
   *
   * @return the dump, one block per thread, each starting with a {@code #} line
   * @throws IOException when the dump cannot be written or read
   */
  static String text() throws IOException {
    Path file = Files.createTempFile("qcaret-threads", ".txt");
    try {
      Files.delete(file);
      ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
          .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
      return Files.readString(file);
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /**
   * Reports whether one thread's stack holds every fragment, for a bounded poll that waits until a
   * worker has reached a call.
   *
   * @param fragments texts that must all appear in one thread's block
   * @return a condition that dumps the threads each time it is tested
   */
  static BooleanSupplier anyStackWith(final String... fragments) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        String dump;
        try {
          dump = text();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        for (String block : dump.split("\n(?=#)")) {
          boolean all = true;
          for (String fragment : fragments) {
            all = all && block.contains(fragment);
          }
          if (all) {
            return true;
          }
        }
        return false;
      }
    };
  }
}
