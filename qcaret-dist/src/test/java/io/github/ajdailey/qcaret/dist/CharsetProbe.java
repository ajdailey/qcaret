package io.github.ajdailey.qcaret.dist;

import java.nio.charset.Charset;

/** Prints each named charset the running Java lacks and exits 1 when any is missing. */
public final class CharsetProbe {
  private CharsetProbe() {}

  /**
   * Checks the charsets.
   *
   * @param names charset names
   */
  public static void main(String[] names) {
    int missing = 0;
    for (String name : names) {
      if (!Charset.isSupported(name)) {
        System.out.println("missing " + name);
        missing++;
      }
    }
    System.out.println("charsets missing: " + missing);
    if (missing > 0) {
      Runtime.getRuntime().halt(1);
    }
  }
}
