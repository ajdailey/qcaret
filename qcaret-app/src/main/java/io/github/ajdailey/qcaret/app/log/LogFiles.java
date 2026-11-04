package io.github.ajdailey.qcaret.app.log;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The debug log file in the settings directory's {@code logs} folder. {@link #attach} and {@link
 * #detach} block; workers call them. {@link #attached} is callable from any thread.
 */
public interface LogFiles {
  /** No log file: {@link #attach} fails and the folder is the working directory. */
  LogFiles NONE =
      new LogFiles() {
        @Override
        public Path directory() {
          return Path.of("").toAbsolutePath();
        }

        @Override
        public boolean attached() {
          return false;
        }

        @Override
        public void attach() throws IOException {
          throw new IOException("No log file in this configuration");
        }

        @Override
        public void detach() {
          // Nothing is attached.
        }
      };

  /**
   * Returns the folder that holds the log file.
   *
   * @return an absolute path; it may not exist yet
   */
  Path directory();

  /**
   * Reports whether log lines go to the file.
   *
   * @return true between a successful {@link #attach} and a {@link #detach} or a write failure
   */
  boolean attached();

  /**
   * Creates the folder owner-only when missing and starts writing the log to the file at DEBUG.
   * Does nothing when attached.
   *
   * @throws IOException when the folder or the file cannot be opened
   */
  void attach() throws IOException;

  /** Stops writing the file and closes it. Does nothing when not attached. */
  void detach();
}
