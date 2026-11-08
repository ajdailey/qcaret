package io.github.ajdailey.qcaret.core.workspace;

import java.nio.file.Path;

/** The user's home folder, the workspace browser's Home. */
public final class HomeFolder {
  /**
   * The system property holding {@code user.home} as the JVM started, set by the launcher before it
   * points {@code user.home} at the settings directory (explicit and portable mode on Linux).
   */
  public static final String PROPERTY = "qcaret.userHome";

  private HomeFolder() {}

  /**
   * Returns the home folder: {@link #PROPERTY} where set, else {@code user.home}.
   *
   * @return the folder's absolute path
   */
  public static Path find() {
    return choose(System.getProperty(PROPERTY), System.getProperty("user.home", ""));
  }

  /**
   * Chooses between the saved home and {@code user.home}.
   *
   * @param saved {@link #PROPERTY}, or null
   * @param userHome {@code user.home}
   * @return the absolute path
   */
  static Path choose(String saved, String userHome) {
    String home = saved == null || saved.isBlank() ? userHome : saved;
    return Path.of(home).toAbsolutePath().normalize();
  }
}
