package io.github.ajdailey.qcaret.core.util.os;

/** macOS and Linux: the running instance raises its own window, so nothing is granted. */
final class NoForegroundRights implements ForegroundRights {
  static final NoForegroundRights INSTANCE = new NoForegroundRights();

  private NoForegroundRights() {}

  @Override
  public void grantAny() {}
}
