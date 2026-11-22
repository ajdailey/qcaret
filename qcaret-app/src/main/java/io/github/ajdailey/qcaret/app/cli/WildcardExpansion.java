package io.github.ajdailey.qcaret.app.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * Recognizes the Windows Java launcher's wildcard expansion. Before {@code main} runs, the launcher
 * replaces each unquoted argument holding {@code *} or {@code ?} with the paths of the files it
 * matches, and keeps it when nothing matches. It reads its command line in the ANSI code page, so a
 * name outside that code page reaches it with {@code ?} in place of those characters, and the
 * expansion then names every file the pattern matches. {@code sun.java.command} holds the arguments
 * before the expansion, after the jar or main class, joined by single spaces.
 */
public final class WildcardExpansion {
  private WildcardExpansion() {}

  /**
   * Returns the patterns the launcher expanded.
   *
   * @param args the arguments {@code main} received
   * @param javaCommand {@code sun.java.command}, or null
   * @param classPath {@code java.class.path}, or null; under {@code -jar} the jar path that starts
   *     {@code javaCommand}
   * @return the space-separated words of {@code javaCommand} after the jar that hold {@code *} or
   *     {@code ?}, when {@code args} are not those arguments; else empty
   */
  public static List<String> patterns(String[] args, String javaCommand, String classPath) {
    if (javaCommand == null || !wildcard(javaCommand)) {
      return List.of();
    }
    String given = args.length == 0 ? "" : " " + String.join(" ", args);
    String tail = javaCommand;
    if (classPath != null && javaCommand.startsWith(classPath)) {
      tail = javaCommand.substring(classPath.length());
      if (tail.equals(given)) {
        return List.of();
      }
    } else if (javaCommand.endsWith(given)) {
      return List.of();
    }
    List<String> patterns = new ArrayList<String>();
    for (String word : tail.split(" ", -1)) {
      if (wildcard(word)) {
        patterns.add(word);
      }
    }
    return patterns;
  }

  private static boolean wildcard(String text) {
    return text.indexOf('*') >= 0 || text.indexOf('?') >= 0;
  }
}
