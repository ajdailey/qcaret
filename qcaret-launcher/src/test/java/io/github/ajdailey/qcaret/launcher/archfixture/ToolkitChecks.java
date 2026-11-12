package io.github.ajdailey.qcaret.launcher.archfixture;

import org.eclipse.swt.SWT;

/** Asks toolkit platform questions outside the views; never instantiated. */
final class ToolkitChecks {
  private ToolkitChecks() {}

  static boolean gtk() {
    return "gtk".equals(SWT.getPlatform());
  }

  static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }
}
