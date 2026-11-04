package io.github.ajdailey.qcaret.app.cli;

/** The QCaret version, from the {@code Implementation-Version} of this module's jar manifest. */
public final class Version {
  private Version() {}

  /**
   * Returns the project version.
   *
   * @return for example {@code 0.1.0}; {@code unknown} when the class was not loaded from a jar
   */
  public static String get() {
    String version = Version.class.getPackage().getImplementationVersion();
    return version == null ? "unknown" : version;
  }
}
