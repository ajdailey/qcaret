package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;

/**
 * {@link SafePaths} refused a path that opens a Windows device ({@code DeviceNames.opensDevice}): a
 * reserved name such as {@code CON} or {@code nul.txt}, or a {@code \\.\} or {@code \\?\} prefix.
 */
public final class DevicePathException extends IOException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception with the message {@code Path names a Windows device}. */
  public DevicePathException() {
    super("Path names a Windows device");
  }
}
