package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Each platform's busy cursor, so a Linux run checks the win32 and cocoa choices too. cocoa SWT
 * shows {@code CURSOR_APPSTARTING} as a plain arrow.
 */
class BusyCursorTest {
  @Test
  void eachPlatformNamesItsCursor() {
    assertEquals(SWT.CURSOR_APPSTARTING, BusyCursor.of("win32").style());
    assertEquals(SWT.CURSOR_APPSTARTING, BusyCursor.of("gtk").style());
    assertEquals(SWT.CURSOR_WAIT, BusyCursor.of("cocoa").style());
    assertEquals(BusyCursor.of(SWT.getPlatform()).style(), BusyCursor.PLATFORM.style());
  }

  @Test
  void anUnknownPlatformIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            BusyCursor.of("motif");
          }
        });
  }
}
