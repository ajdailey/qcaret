package io.github.ajdailey.qcaret.bootstrap;

/** The six platforms QCaret runs on, each named {@code <os>-<arch>} as in the build. */
public enum Target {
  LINUX_X86_64("linux-x86_64"),
  LINUX_AARCH64("linux-aarch64"),
  WINDOWS_X86_64("windows-x86_64"),
  WINDOWS_AARCH64("windows-aarch64"),
  MACOS_X86_64("macos-x86_64"),
  MACOS_AARCH64("macos-aarch64");

  private final String id;

  Target(String id) {
    this.id = id;
  }

  /**
   * Returns the build name, which also names the nested fragment and natives entries.
   *
   * @return the name, for example {@code linux-x86_64}
   */
  public String id() {
    return id;
  }

  /**
   * Reports whether the target is macOS, where SWT needs the process main thread.
   *
   * @return true for both macOS targets
   */
  public boolean isMac() {
    return this == MACOS_X86_64 || this == MACOS_AARCH64;
  }

  /**
   * Reports whether the target is Windows.
   *
   * @return true for both Windows targets
   */
  public boolean isWindows() {
    return this == WINDOWS_X86_64 || this == WINDOWS_AARCH64;
  }

  /**
   * Maps the JVM's {@code os.name} and {@code os.arch} to a target.
   *
   * @param osName {@code Linux}, a name starting {@code Windows}, or {@code Mac OS X}
   * @param osArch {@code amd64}, {@code x86_64} or {@code aarch64}
   * @return the target, or null for any other pair
   */
  public static Target of(String osName, String osArch) {
    if (osName == null || osArch == null) {
      return null;
    }
    boolean x64 = "amd64".equals(osArch) || "x86_64".equals(osArch);
    boolean arm64 = "aarch64".equals(osArch);
    if (!x64 && !arm64) {
      return null;
    }
    if (osName.equals("Linux")) {
      return x64 ? LINUX_X86_64 : LINUX_AARCH64;
    }
    if (osName.startsWith("Windows")) {
      return x64 ? WINDOWS_X86_64 : WINDOWS_AARCH64;
    }
    if (osName.equals("Mac OS X")) {
      return x64 ? MACOS_X86_64 : MACOS_AARCH64;
    }
    return null;
  }

  /**
   * Returns the message for an unsupported platform.
   *
   * @param osName the running {@code os.name}
   * @param osArch the running {@code os.arch}
   * @return the message, without a line terminator
   */
  static String unsupportedMessage(String osName, String osArch) {
    return "QCaret supports Linux, Windows and macOS on x86_64 and aarch64; this Java reports"
        + " os.name="
        + osName
        + ", os.arch="
        + osArch
        + ".";
  }
}
