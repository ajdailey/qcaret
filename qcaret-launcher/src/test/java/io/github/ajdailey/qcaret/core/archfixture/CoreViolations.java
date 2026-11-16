package io.github.ajdailey.qcaret.core.archfixture;

import io.github.ajdailey.qcaret.app.i18n.UiText;

/** Core classes that each break one architecture rule; never instantiated. */
final class CoreViolations {
  private CoreViolations() {}

  /** Uses the application module. */
  static final class UsesApp {
    String title() {
      return UiText.WINDOW_TITLE.text();
    }
  }

  /** Loads a class by name and opens its constructor. */
  static final class Reflects {
    Object load() throws ReflectiveOperationException {
      return Class.forName("mock.Type").getDeclaredConstructor();
    }
  }
}
