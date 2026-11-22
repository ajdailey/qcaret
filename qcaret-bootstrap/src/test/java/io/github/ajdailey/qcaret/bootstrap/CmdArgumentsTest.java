package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CmdArgumentsTest {
  @Test
  void splitsByTheCRuntimeRules() {
    // The examples of Microsoft's "Parsing C++ command-line arguments".
    assertEquals(List.of("abc", "d", "e"), CmdArguments.split("\"abc\" d e"));
    assertEquals(List.of("a\\\\\\b", "de fg", "h"), CmdArguments.split("a\\\\\\b d\"e f\"g h"));
    assertEquals(List.of("a\\\"b", "c", "d"), CmdArguments.split("a\\\\\\\"b c d"));
    assertEquals(List.of("a\\\\b c", "d", "e"), CmdArguments.split("a\\\\\\\\\"b c\" d e"));
    assertEquals(List.of("ab\" c d"), CmdArguments.split("a\"b\"\" c d"));
  }

  @Test
  void keepsEmptyQuotedArgumentsAndDropsSurroundingSpace() {
    assertEquals(List.of("", "x"), CmdArguments.split("  \"\"\tx "));
    assertEquals(List.of(), CmdArguments.split(" "));
    assertEquals(List.of("C:\\dir\\"), CmdArguments.split("C:\\dir\\"));
  }

  @Test
  void passesWildcardsAndNamesOutsideTheCodePageUnchanged() {
    String cjk = "\u65e5\u672c.txt";
    assertEquals(
        List.of(cjk, "*.txt", "a&b", "x y.txt", "100%.txt", "e!f"),
        CmdArguments.split(" " + cjk + " *.txt a&b \"x y.txt\" 100%.txt e!f"));
  }

  @Test
  void readsTheVariableOnlyUnderQcaretCmdWithNoArguments() {
    Map<String, String> env = Map.of(CmdArguments.VARIABLE, " -m \"a b.txt\"");
    String[] none = new String[0];
    assertArrayEquals(new String[] {"-m", "a b.txt"}, CmdArguments.of(none, CmdArguments.CMD, env));
    assertSame(none, CmdArguments.of(none, null, env));
    assertSame(none, CmdArguments.of(none, CmdArguments.CMD, Map.of()));
    String[] given = {"f.txt"};
    assertSame(given, CmdArguments.of(given, CmdArguments.CMD, env));
  }
}
