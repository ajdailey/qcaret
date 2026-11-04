package io.github.ajdailey.qcaret.bench;

import java.util.SplittableRandom;

/** Generated benchmark input; the same kind, size and seed give the same text. */
public enum Corpus {
  /** Source-like ASCII lines of 80 chars including the LF. */
  ASCII,
  /** {@link #ASCII} with 30% of the chars other than LF outside Latin-1. */
  MIXED,
  /** One line of source-like ASCII with no line break. */
  LONGLINE;

  private static final String[] WORDS = {
    "int", "return", "value", "count", "(", ")", "{", "}", ";", "=", "+", "if", "for", "String",
    "name", "index", "final", "static", "void", "while", "buffer", "offset", "length", "0", "1",
    "//", "null", "new", "this", ".", ","
  };

  /** Chars per MiB of text. */
  public static final int MIB = 1024 * 1024;

  /**
   * Generates text of exactly {@code chars} chars.
   *
   * @param chars the length
   * @param seed the random seed
   * @return the text
   */
  public String generate(int chars, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    StringBuilder sb = new StringBuilder(chars + 80);
    StringBuilder line = new StringBuilder(80);
    while (sb.length() < chars) {
      line.setLength(0);
      if (this != LONGLINE) {
        line.append("            ", 0, 4 * random.nextInt(4));
      }
      while (line.length() < 79) {
        line.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
      }
      line.setLength(79);
      sb.append(line);
      sb.append(this == LONGLINE ? ' ' : '\n');
    }
    sb.setLength(chars);
    if (this == MIXED) {
      for (int i = 0; i < chars; i++) {
        if (sb.charAt(i) != '\n' && random.nextInt(10) < 3) {
          sb.setCharAt(i, nonLatin1(random));
        }
      }
    }
    return sb.toString();
  }

  private static char nonLatin1(SplittableRandom random) {
    switch (random.nextInt(3)) {
      case 0:
        return (char) (0x03B1 + random.nextInt(25));
      case 1:
        return (char) (0x0430 + random.nextInt(32));
      default:
        return (char) (0x4E00 + random.nextInt(0x1000));
    }
  }
}
