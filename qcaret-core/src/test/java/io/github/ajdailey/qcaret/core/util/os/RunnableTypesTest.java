package io.github.ajdailey.qcaret.core.util.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class RunnableTypesTest {
  @Test
  void everyWindowsExtensionRuns() {
    assertEquals(41, RunnableTypes.WINDOWS.size());
    for (String extension : RunnableTypes.WINDOWS) {
      assertTrue(RunnableTypes.windows("file" + extension, null), extension);
      assertTrue(
          RunnableTypes.windows("FILE" + extension.toUpperCase(Locale.ROOT), null), extension);
    }
    assertFalse(RunnableTypes.windows("notes.txt", null));
    assertFalse(RunnableTypes.windows("notes.wsx", null));
  }

  @Test
  void windowsDropsTrailingDotsAndSpaces() {
    assertTrue(RunnableTypes.windows("tool.jar.", null));
    assertTrue(RunnableTypes.windows("run.bat . ", null));
    assertFalse(RunnableTypes.windows(". .", null));
  }

  @Test
  void pathextAddsItsEntries() {
    String pathext = ".COM;.EXE; .FOO ;;TXT;.";
    assertTrue(RunnableTypes.windows("script.foo", pathext));
    assertTrue(RunnableTypes.windows("script.FOO.", pathext));
    assertFalse(RunnableTypes.windows("script.foo", null));
    assertFalse(RunnableTypes.windows("notes.txt", pathext));
    assertFalse(RunnableTypes.windows("notes.md", pathext));
  }

  @Test
  void everyMacExtensionRuns() {
    assertEquals(7, RunnableTypes.MAC.size());
    for (String extension : RunnableTypes.MAC) {
      assertTrue(RunnableTypes.mac("file" + extension), extension);
      assertTrue(RunnableTypes.mac("FILE" + extension.toUpperCase(Locale.ROOT)), extension);
    }
    assertFalse(RunnableTypes.mac("notes.txt"));
    assertFalse(RunnableTypes.mac("setup.exe"));
  }
}
