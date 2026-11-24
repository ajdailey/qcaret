package io.github.ajdailey.qcaret.core.archfixture;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

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

  /** Declares collection classes as a field, a generic parameter argument and a return type. */
  static final class DeclaresClasses {
    final ArrayList<String> items = new ArrayList<String>();
    final List<String> names = new ArrayList<String>();

    int index(Map<String, TreeSet<String>> words) {
      return words.size();
    }

    ArrayDeque<String> pending() {
      return new ArrayDeque<String>();
    }
  }
}
