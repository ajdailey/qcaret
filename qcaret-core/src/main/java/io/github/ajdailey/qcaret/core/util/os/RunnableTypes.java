package io.github.ajdailey.qcaret.core.util.os;

import java.util.List;
import java.util.Locale;

/**
 * File name extensions the system runs, or follows to another target, when asked to open the file
 * with its default application. Extensions are lower case with the dot. Both lists are denylists: a
 * program installed later can register another extension to run.
 */
public final class RunnableTypes {
  /** Extensions Windows runs; {@code PATHEXT} adds to them. */
  public static final List<String> WINDOWS =
      List.of(
          ".appref-ms",
          ".application",
          ".bat",
          ".chm",
          ".cmd",
          ".com",
          ".cpl",
          ".diagcab",
          ".exe",
          ".hta",
          ".inf",
          ".jar",
          ".jnlp",
          ".js",
          ".jse",
          ".library-ms",
          ".lnk",
          ".msc",
          ".msi",
          ".msp",
          ".mst",
          ".pif",
          ".ps1",
          ".psc1",
          ".py",
          ".pyw",
          ".reg",
          ".scf",
          ".scr",
          ".sct",
          ".settingcontent-ms",
          ".url",
          ".vb",
          ".vbe",
          ".vbs",
          ".website",
          ".ws",
          ".wsc",
          ".wsf",
          ".wsh",
          ".xll");

  /**
   * Extensions macOS runs or follows without the execute bit: Terminal runs a {@code .terminal}
   * file's stored command, a {@code .command} and a {@code .tool}, Jar Launcher runs a {@code
   * .jar}, and the location files open their target.
   */
  public static final List<String> MAC =
      List.of(".command", ".fileloc", ".inetloc", ".jar", ".terminal", ".tool", ".webloc");

  private RunnableTypes() {}

  /**
   * Reports whether Windows runs a file by its name. Trailing dots and spaces are dropped first, as
   * Windows drops them.
   *
   * @param fileName the file name, without directories
   * @param pathext the {@code PATHEXT} value, entries separated by {@code ;}; null for none
   * @return true for an extension in {@link #WINDOWS} or a {@code PATHEXT} entry starting with
   *     {@code .}, ignoring case
   */
  public static boolean windows(String fileName, String pathext) {
    String name = trimEnd(fileName).toLowerCase(Locale.ROOT);
    if (endsWithAny(name, WINDOWS)) {
      return true;
    }
    if (pathext == null) {
      return false;
    }
    for (String entry : pathext.split(";")) {
      String extension = entry.strip().toLowerCase(Locale.ROOT);
      if (extension.length() > 1 && extension.startsWith(".") && name.endsWith(extension)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Reports whether macOS runs a file by its name, whatever its execute bit.
   *
   * @param fileName the file name, without directories
   * @return true for an extension in {@link #MAC}, ignoring case
   */
  public static boolean mac(String fileName) {
    return endsWithAny(fileName.toLowerCase(Locale.ROOT), MAC);
  }

  private static boolean endsWithAny(String name, List<String> extensions) {
    for (String extension : extensions) {
      if (name.endsWith(extension)) {
        return true;
      }
    }
    return false;
  }

  private static String trimEnd(String name) {
    int end = name.length();
    while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
      end--;
    }
    return name.substring(0, end);
  }
}
