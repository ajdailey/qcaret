package io.github.ajdailey.qcaret.app.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Macro names: length, chars, outer spaces, Windows device names and case clashes. */
class MacroNamesTest {
  @Test
  void validNamesPass() {
    for (String name :
        new String[] {"a", "Wrap Lines", "fix-up_2", "x".repeat(64), "\u00e9t\u00e9 1", "COM10"}) {
      assertNull(MacroNames.check(name), name);
    }
  }

  @Test
  void formProblemsAreNamed() {
    assertEquals(MacroNames.Problem.LENGTH, MacroNames.check(""));
    assertEquals(MacroNames.Problem.LENGTH, MacroNames.check("x".repeat(65)));
    for (String name : new String[] {" a", "a ", "a.b", "a/b", "a\\b", "a:b", "a*", "tab\t"}) {
      assertEquals(MacroNames.Problem.CHARS, MacroNames.check(name), name);
    }
    for (String name : new String[] {"CON", "nul", "Aux", "prn", "com1", "LPT9"}) {
      assertEquals(MacroNames.Problem.DEVICE, MacroNames.check(name), name);
    }
  }

  @Test
  void aNameOtherwiseUsedIgnoringCaseIsTaken() {
    assertEquals(MacroNames.Problem.TAKEN, MacroNames.check("Wrap", List.of("x", "wRAP")));
    assertNull(MacroNames.check("Wrap2", List.of("wrap")));
    assertEquals(MacroNames.Problem.DEVICE, MacroNames.check("con", List.of("con")));
  }
}
