package io.github.ajdailey.qcaret.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WildcardExpansionTest {
  private static final String JAR = "C:\\q\\lib\\qcaret.jar";

  @Test
  void anExpandedPatternIsReported() {
    // As measured on Windows: an unquoted ??.txt beside zz.txt and the CJK file it stood for.
    assertEquals(
        List.of("D:\\n\\??.txt"),
        WildcardExpansion.patterns(
            new String[] {"-m", "D:\\n\\zz.txt", "D:\\n\\\u65e5\u672c.txt"},
            JAR + " -m D:\\n\\??.txt",
            JAR));
  }

  @Test
  void argumentsMatchingTheCommandAreNotExpanded() {
    String[] quoted = {"D:\\n\\??.txt", "x y.txt"};
    assertEquals(
        List.of(), WildcardExpansion.patterns(quoted, JAR + " D:\\n\\??.txt x y.txt", JAR));
    assertEquals(List.of(), WildcardExpansion.patterns(new String[0], JAR, JAR));
    assertEquals(
        List.of(), WildcardExpansion.patterns(new String[] {"a.txt"}, JAR + " a.txt", JAR));
    assertEquals(List.of(), WildcardExpansion.patterns(new String[] {"*"}, null, null));
  }

  @Test
  void aMainClassCommandIsComparedByItsEnd() {
    String main = "io.github.ajdailey.qcaret.launcher.Main";
    assertEquals(
        List.of(), WildcardExpansion.patterns(new String[] {"*.txt"}, main + " *.txt", "a;b"));
    assertEquals(
        List.of("*.txt"),
        WildcardExpansion.patterns(new String[] {"a.txt", "b.txt"}, main + " *.txt", "a;b"));
  }
}
