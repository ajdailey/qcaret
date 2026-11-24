package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.util.regex.Pattern;

/** File names on Windows: trailing dots and spaces, device names and UNC shares. */
final class WindowsDeviceNames implements DeviceNames {
  static final WindowsDeviceNames INSTANCE = new WindowsDeviceNames();

  private static final Pattern RESERVED =
      Pattern.compile(
          "(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]|CONIN\\$|CONOUT\\$)(\\..*)?",
          Pattern.CASE_INSENSITIVE);

  private WindowsDeviceNames() {}

  @Override
  public void checkNewName(String name) throws IOException {
    if (name.endsWith(".") || name.endsWith(" ")) {
      throw new IOException("Name ends in a dot or space, which Windows drops");
    }
  }

  @Override
  public boolean opensDevice(String path) {
    return device(path);
  }

  @Override
  public void checkShare(String folder, String name) throws IOException {
    String share = uncShare(name);
    if (share != null && !sameShare(share, uncShare(folder))) {
      throw new IOException("names a network share the list is not on");
    }
  }

  /**
   * Reports whether a path names a Windows device or uses a device or raw namespace prefix.
   *
   * @param path the path text
   * @return true when Windows would open a device rather than a file
   */
  static boolean device(String path) {
    String text = path.replace('/', '\\');
    if (text.startsWith("\\\\.\\") || text.startsWith("\\\\?\\")) {
      return true;
    }
    for (String part : text.split("[\\\\:]")) {
      if (RESERVED.matcher(part.strip()).matches()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the {@code \\server\share} prefix of a UNC path, with either separator.
   *
   * @param path the path text
   * @return the prefix with backslashes; the whole text when it starts with two separators but
   *     names no share; null when it does not start with two separators
   */
  static String uncShare(String path) {
    String text = path.replace('/', '\\');
    if (!text.startsWith("\\\\")) {
      return null;
    }
    int server = text.indexOf('\\', 2);
    int share = server < 0 ? -1 : text.indexOf('\\', server + 1);
    return share < 0 ? text : text.substring(0, share);
  }

  /**
   * Compares two share prefixes, ignoring the case of ASCII letters only: a Unicode case mapping
   * could match a different host's name.
   *
   * @param a a prefix
   * @param b a prefix, or null
   * @return true when they name the same share
   */
  static boolean sameShare(String a, String b) {
    if (b == null || a.length() != b.length()) {
      return false;
    }
    for (int i = 0; i < a.length(); i++) {
      if (asciiLower(a.charAt(i)) != asciiLower(b.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }
}
