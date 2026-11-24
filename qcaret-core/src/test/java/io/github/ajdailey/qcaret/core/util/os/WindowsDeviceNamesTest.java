package io.github.ajdailey.qcaret.core.util.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The Windows name forms {@link WindowsDeviceNames} parses, checked on every system. */
class WindowsDeviceNamesTest {
  @Test
  void windowsDeviceForms() {
    for (String path :
        List.of(
            "CON",
            "nul.txt",
            "C:\\logs\\COM1",
            "\\\\.\\PhysicalDrive0",
            "\\\\?\\GLOBALROOT\\x",
            "lpt9.txt")) {
      assertTrue(WindowsDeviceNames.device(path), path);
    }
    for (String path : List.of("C:\\docs\\console.txt", "context", "COM10", "notes.txt")) {
      assertFalse(WindowsDeviceNames.device(path), path);
    }
  }

  @Test
  void uncShareTakesServerAndShareWithEitherSeparator() {
    assertEquals("\\\\SRV\\Share", WindowsDeviceNames.uncShare("\\\\SRV\\Share\\dir\\x.txt"));
    assertEquals("\\\\srv\\share", WindowsDeviceNames.uncShare("//srv/share/x.txt"));
    assertEquals("\\\\srv\\share", WindowsDeviceNames.uncShare("\\/srv\\share"));
    assertEquals("\\\\srv", WindowsDeviceNames.uncShare("\\\\srv"));
    for (String path : List.of("C:\\lists\\x", "\\x", "/home/x", "x.txt", "")) {
      assertNull(WindowsDeviceNames.uncShare(path), path);
    }
  }

  /** Only ASCII letters fold: the Kelvin sign lower-cases to {@code k} but names another host. */
  @Test
  void sameShareFoldsAsciiCaseOnly() {
    assertTrue(WindowsDeviceNames.sameShare("\\\\SRV\\Share", "\\\\srv\\sHARE"));
    assertFalse(WindowsDeviceNames.sameShare("\\\\\u212Asrv\\share", "\\\\ksrv\\share"));
    assertFalse(WindowsDeviceNames.sameShare("\\\\srv\\share", "\\\\srv\\share2"));
    assertFalse(WindowsDeviceNames.sameShare("\\\\srv\\share", null));
  }
}
