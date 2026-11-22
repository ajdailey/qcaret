package io.github.ajdailey.qcaret.app.cli;

/** The QCaret version, from the {@code Implementation-Version} of this module's jar manifest. */
public final class Version {
  private Version() {}

  /**
   * Returns the project version.
   *
   * @return for example {@code 0.2.0}, or {@code 0.2.0-SNAPSHOT+3290f82} for a CI build of that
   *     commit; {@code unknown} when the class was not loaded from a jar
   */
  public static String get() {
    String version = Version.class.getPackage().getImplementationVersion();
    return version == null ? "unknown" : version;
  }
}
