package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Every answer of every platform's traits, as the view classes' platform branches gave them, so a
 * Linux run checks the win32 and cocoa classes too.
 */
class PlatformTraitsTest {
  /** Rows in the order of {@link #answers}. */
  private static final String[] QUESTIONS = {
    "name",
    "monospaceFamily",
    "monospaceSize",
    "caretBlend",
    "typesWithAlt",
    "systemAppMenu",
    "checksPrinterList",
    "opensBrowser",
    "positionsWindows",
    "remeasuresTrim",
    "launchesByExtension",
    "explainsUnsupportedStacking",
    "menuImages",
    "padsMenuImages",
    "fixedMenuInk",
    "fadesDisabledIcons",
    "sizesToolPictures",
    "switchesThemeLive",
    "followsSystemAppearance",
    "raisesLinkContrast",
    "lightChecksUnderDark",
    "colorsDialogs",
    "macKeys",
    "linuxKeys",
    "opensPrintDialog",
    "busyCursor"
  };

  private static List<Object> answers(PlatformTraits traits) {
    List<Object> answers = new ArrayList<Object>();
    answers.add(traits.name());
    answers.add(traits.monospaceFamily());
    answers.add(traits.monospaceSize());
    answers.add(traits.caretBlend());
    answers.add(traits.typesWithAlt());
    answers.add(traits.systemAppMenu());
    answers.add(traits.checksPrinterList());
    answers.add(traits.opensBrowser());
    answers.add(traits.positionsWindows());
    answers.add(traits.remeasuresTrim());
    answers.add(traits.launchesByExtension());
    answers.add(traits.explainsUnsupportedStacking());
    answers.add(traits.menuImages());
    answers.add(traits.padsMenuImages());
    answers.add(traits.fixedMenuInk());
    answers.add(traits.fadesDisabledIcons());
    answers.add(traits.sizesToolPictures());
    answers.add(traits.switchesThemeLive());
    answers.add(traits.followsSystemAppearance());
    answers.add(traits.raisesLinkContrast());
    answers.add(traits.lightChecksUnderDark());
    answers.add(traits.colorsDialogs());
    answers.add(traits.macKeys());
    answers.add(traits.linuxKeys());
    answers.add(traits.opensPrintDialog());
    answers.add(traits.busyCursor());
    return answers;
  }

  private static void check(PlatformTraits traits, Object... expected) {
    List<Object> actual = answers(traits);
    for (int i = 0; i < QUESTIONS.length; i++) {
      assertEquals(expected[i], actual.get(i), traits.name() + " " + QUESTIONS[i]);
    }
  }

  @Test
  void win32() {
    check(
        PlatformTraits.of("win32", false),
        "win32",
        "Consolas",
        10,
        CaretBlend.XOR,
        false,
        false,
        true,
        true,
        true,
        true,
        true,
        false,
        true,
        false,
        true,
        false,
        false,
        false,
        false,
        false,
        true,
        true,
        false,
        false,
        true,
        SWT.CURSOR_APPSTARTING);
  }

  @Test
  void cocoa() {
    check(
        PlatformTraits.of("cocoa", false),
        "cocoa",
        "Menlo",
        12,
        CaretBlend.DIFFERENCE,
        true,
        true,
        true,
        true,
        true,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        true,
        false,
        true,
        SWT.CURSOR_WAIT);
  }

  @Test
  void gtk3() {
    String backend = System.getProperty(PlatformTraits.GDK_BACKEND);
    check(
        PlatformTraits.of("gtk", false),
        "gtk",
        "Monospace",
        10,
        CaretBlend.DIFFERENCE,
        false,
        false,
        false,
        true,
        !"wayland".equals(backend),
        false,
        false,
        true,
        true,
        true,
        false,
        true,
        false,
        true,
        false,
        true,
        false,
        false,
        false,
        true,
        true,
        SWT.CURSOR_APPSTARTING);
  }

  @Test
  void gtk4() {
    check(
        PlatformTraits.of("gtk", true),
        "gtk",
        "Monospace",
        10,
        CaretBlend.OVER,
        false,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        true,
        true,
        false,
        true,
        true,
        true,
        false,
        true,
        false,
        false,
        false,
        true,
        false,
        SWT.CURSOR_APPSTARTING);
  }

  @Test
  void gtk3PositionsWindowsExceptUnderWayland() {
    String key = PlatformTraits.GDK_BACKEND;
    String saved = System.getProperty(key);
    PlatformTraits traits = PlatformTraits.of("gtk", false);
    try {
      System.setProperty(key, "wayland");
      assertEquals(false, traits.positionsWindows());
      System.setProperty(key, "x11");
      assertEquals(true, traits.positionsWindows());
    } finally {
      if (saved == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, saved);
      }
    }
  }

  @Test
  void theRunningPlatform() {
    boolean gtk4 = "1".equals(System.getenv("SWT_GTK4"));
    assertEquals(SWT.getPlatform(), PlatformTraits.PLATFORM.name());
    assertEquals(
        PlatformTraits.of(SWT.getPlatform(), gtk4).getClass(), PlatformTraits.PLATFORM.getClass());
  }

  @Test
  void keyNamesFollowCocoaAndTheKeymapFollowsGtk() {
    String[] platforms = {"win32", "cocoa", "gtk"};
    for (String platform : platforms) {
      for (boolean gtk4 : new boolean[] {false, true}) {
        PlatformTraits traits = PlatformTraits.of(platform, gtk4);
        assertEquals("cocoa".equals(platform), traits.macKeys(), platform);
        assertEquals("gtk".equals(platform), traits.linuxKeys(), platform);
      }
    }
    assertEquals("cocoa".equals(SWT.getPlatform()), PlatformTraits.PLATFORM.macKeys());
    assertEquals("gtk".equals(SWT.getPlatform()), PlatformTraits.PLATFORM.linuxKeys());
  }

  @Test
  void gtk4IsReadOnlyOnGtk() {
    assertInstanceOf(Gtk4Traits.class, PlatformTraits.of("gtk", true));
    assertInstanceOf(Gtk3Traits.class, PlatformTraits.of("gtk", false));
    assertInstanceOf(Win32Traits.class, PlatformTraits.of("win32", true));
    assertInstanceOf(CocoaTraits.class, PlatformTraits.of("cocoa", true));
  }

  @Test
  void anUnknownPlatformIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            PlatformTraits.of("motif", false);
          }
        });
  }
}
