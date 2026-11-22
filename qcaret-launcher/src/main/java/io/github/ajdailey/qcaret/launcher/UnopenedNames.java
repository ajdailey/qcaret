package io.github.ajdailey.qcaret.launcher;

import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.util.List;

/**
 * Command-line names a launch does not open, and the message that says so. After the Java
 * launcher's wildcard expansion ({@link io.github.ajdailey.qcaret.app.cli.WildcardExpansion}) the
 * launch opens no command-line file and no comparison, since an expanded argument can name a file
 * other than the one the user chose.
 *
 * @param expanded the patterns the Java launcher expanded
 * @param unpassed the file arguments holding {@code ?} or {@code *} that are not valid paths
 */
record UnopenedNames(List<String> expanded, List<String> unpassed) {
  /** Copies both lists. */
  UnopenedNames {
    expanded = List.copyOf(expanded);
    unpassed = List.copyOf(unpassed);
  }

  /**
   * Returns the request this launch carries out.
   *
   * @param request the parsed request
   * @return {@code request} without its files and comparison when a pattern was expanded, else
   *     {@code request}
   */
  StartupRequest apply(StartupRequest request) {
    if (expanded.isEmpty()) {
      return request;
    }
    return new StartupRequest(
        List.of(),
        request.width(),
        request.height(),
        request.x(),
        request.y(),
        request.settingsDirectory(),
        request.multi(),
        request.newWindow(),
        request.session(),
        null,
        null);
  }

  /**
   * Returns the message.
   *
   * @return the message, or null when every name is opened
   */
  String message() {
    if (!expanded.isEmpty()) {
      return UiText.LAUNCH_NAMES_EXPANDED.format(String.join(", ", expanded));
    }
    if (!unpassed.isEmpty()) {
      return UiText.LAUNCH_NAMES_UNPASSED.format(String.join(", ", unpassed));
    }
    return null;
  }
}
