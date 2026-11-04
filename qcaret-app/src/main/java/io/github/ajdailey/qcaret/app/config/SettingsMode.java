package io.github.ajdailey.qcaret.app.config;

/** How the settings directory was chosen. */
public enum SettingsMode {
  /** {@code --settings <dir>}. */
  EXPLICIT,
  /** {@code <qcaret.home>/qcaret.conf} exists, so the installation directory holds the settings. */
  PORTABLE,
  /** The per-user configuration directory of the OS. */
  PER_USER
}
