package io.github.ajdailey.qcaret.core.util.os;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link ForegroundRights}: each system's class, and on Windows user32 {@code
 * AllowSetForegroundWindow} linking through {@code java.lang.foreign}. A failure after a JDK or
 * Windows change names the missing member.
 */
class ForegroundRightsTest {
  @Test
  void onlyWindowsGrantsRights() throws IOException {
    assertSame(WindowsForegroundRights.INSTANCE, OsTraits.of("Windows 11").foregroundRights());
    assertSame(NoForegroundRights.INSTANCE, OsTraits.of("Mac OS X").foregroundRights());
    assertSame(NoForegroundRights.INSTANCE, OsTraits.of("Linux").foregroundRights());
    NoForegroundRights.INSTANCE.grantAny();
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void allowSetForegroundWindowLinks() throws ReflectiveOperationException {
    assertNotNull(WindowsForegroundRights.link(WindowsForegroundRights.FOREIGN));
  }
}
