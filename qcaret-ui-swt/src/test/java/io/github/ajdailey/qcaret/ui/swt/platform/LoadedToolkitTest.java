package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The traits chosen from the GTK version SWT records, and the GTK 4 fallback it reports. */
class LoadedToolkitTest {
  @Test
  void gtkTraitsFollowTheMajorVersion() {
    assertInstanceOf(Gtk4Traits.class, LoadedToolkit.choose("gtk", "4.14.5"));
    assertInstanceOf(Gtk4Traits.class, LoadedToolkit.choose("gtk", "4.0.0"));
    assertInstanceOf(Gtk3Traits.class, LoadedToolkit.choose("gtk", "3.24.41"));
    assertInstanceOf(Gtk3Traits.class, LoadedToolkit.choose("gtk", "40.1.0"));
    assertInstanceOf(Gtk3Traits.class, LoadedToolkit.choose("gtk", ""));
    assertInstanceOf(Gtk3Traits.class, LoadedToolkit.choose("gtk", null));
  }

  @Test
  void otherPlatformsIgnoreTheVersion() {
    assertInstanceOf(Win32Traits.class, LoadedToolkit.choose("win32", "4.14.5"));
    assertInstanceOf(CocoaTraits.class, LoadedToolkit.choose("cocoa", null));
  }

  @Test
  void fallbackOnlyWhenGtk4RequestedAndGtk3Loaded() {
    assertTrue(LoadedToolkit.fellBack(true, "3.24.41"));
    assertFalse(LoadedToolkit.fellBack(true, "4.14.5"));
    assertFalse(LoadedToolkit.fellBack(false, "3.24.41"));
    assertFalse(LoadedToolkit.fellBack(true, null));
  }
}
