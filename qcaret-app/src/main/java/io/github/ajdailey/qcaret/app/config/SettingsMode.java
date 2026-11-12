package io.github.ajdailey.qcaret.app.config;

/** How the settings directory was chosen. */
public enum SettingsMode {
  /** {@code --settings <dir>}. */
  EXPLICIT,
  /**
   * {@code qcaret.conf} exists in the installation directory or, on Linux, beside the launcher
   * script; that directory holds the settings.
   */
  PORTABLE,
  /** The per-user configuration directory of the OS. */
  PER_USER
}
