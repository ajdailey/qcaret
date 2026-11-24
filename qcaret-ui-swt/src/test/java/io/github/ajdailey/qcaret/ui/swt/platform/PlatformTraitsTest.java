package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Every value and every seam class of every platform's traits, and the seams' answers that need no
 * Display, so a Linux run checks the win32 and cocoa classes too.
 */
class PlatformTraitsTest {
  /** The boolean value traits; any other boolean method would be a behavior switch. */
  private static final List<String> BOOLEAN_VALUES = List.of("macKeys", "linuxKeys");

  private static final PlatformTraits WIN32 = PlatformTraits.of("win32", false);
  private static final PlatformTraits COCOA = PlatformTraits.of("cocoa", false);
  private static final PlatformTraits GTK3 = PlatformTraits.of("gtk", false);
  private static final PlatformTraits GTK4 = PlatformTraits.of("gtk", true);

  private static List<Object> values(PlatformTraits traits) {
    List<Object> values = new ArrayList<Object>();
    values.add(traits.name());
    values.add(traits.monospaceFamily());
    values.add(traits.monospaceSize());
    values.add(traits.caretBlend());
    values.add(traits.macKeys());
    values.add(traits.linuxKeys());
    values.add(traits.busyCursor());
    return values;
  }

  private static List<String> seams(PlatformTraits traits) {
    List<String> seams = new ArrayList<String>();
    seams.add(traits.dialogColors().getClass().getSimpleName());
    seams.add(traits.themeFollow(null).getClass().getSimpleName());
    seams.add(traits.menuBehavior().getClass().getSimpleName());
    seams.add(traits.toolItemSizing().getClass().getSimpleName());
    seams.add(traits.keyBehavior().getClass().getSimpleName());
    seams.add(traits.browserBehavior().getClass().getSimpleName());
    seams.add(traits.printSupport().getClass().getSimpleName());
    seams.add(traits.windowPlacement().getClass().getSimpleName());
    seams.add(traits.nativeNames().getClass().getSimpleName());
    seams.add(traits.accessibleLabels().getClass().getSimpleName());
    seams.add(traits.guideDisplay().getClass().getSimpleName());
    seams.add(traits.launchDocuments().getClass().getSimpleName());
    return seams;
  }

  @Test
  void values() {
    assertEquals(
        List.of("win32", "Consolas", 10, CaretBlend.XOR, false, false, SWT.CURSOR_APPSTARTING),
        values(WIN32));
    assertEquals(
        List.of("cocoa", "Menlo", 12, CaretBlend.DIFFERENCE, true, false, SWT.CURSOR_WAIT),
        values(COCOA));
    assertEquals(
        List.of("gtk", "Monospace", 10, CaretBlend.DIFFERENCE, false, true, SWT.CURSOR_APPSTARTING),
        values(GTK3));
    assertEquals(
        List.of("gtk", "Monospace", 10, CaretBlend.OVER, false, true, SWT.CURSOR_APPSTARTING),
        values(GTK4));
    assertTrue(((NativeWindowPlacement) COCOA.windowPlacement()).positioned());
    assertFalse(((NativeWindowPlacement) GTK4.windowPlacement()).positioned());
  }

  @Test
  void seams() {
    assertEquals(
        List.of(
            "Win32DialogColors",
            "Win32ThemeFollow",
            "Win32MenuBehavior",
            "NativeToolItemSizing",
            "Win32KeyBehavior",
            "Win32BrowserBehavior",
            "Win32PrintSupport",
            "Win32WindowPlacement",
            "NoNativeNames",
            "NativeAccessibleLabels",
            "BrowserGuideDisplay",
            "NativeLaunchDocuments"),
        seams(WIN32));
    assertEquals(
        List.of(
            "NativeDialogColors",
            "CocoaThemeFollow",
            "CocoaMenuBehavior",
            "NativeToolItemSizing",
            "CocoaKeyBehavior",
            "CocoaBrowserBehavior",
            "CocoaPrintSupport",
            "NativeWindowPlacement",
            "NoNativeNames",
            "CocoaAccessibleLabels",
            "BrowserGuideDisplay",
            "CocoaLaunchDocuments"),
        seams(COCOA));
    assertEquals(
        List.of(
            "NativeDialogColors",
            "GtkThemeFollow",
            "Gtk3MenuBehavior",
            "NativeToolItemSizing",
            "Gtk3KeyBehavior",
            "Gtk3BrowserBehavior",
            "Gtk3PrintSupport",
            "GtkWindowPlacement",
            "Gtk3NativeNames",
            "NativeAccessibleLabels",
            "BrowserGuideDisplay",
            "NativeLaunchDocuments"),
        seams(GTK3));
    assertEquals(
        List.of(
            "NativeDialogColors",
            "GtkThemeFollow",
            "Gtk4MenuBehavior",
            "Gtk4ToolItemSizing",
            "Gtk4KeyBehavior",
            "Gtk4BrowserBehavior",
            "Gtk4PrintSupport",
            "GtkWindowPlacement",
            "NoNativeNames",
            "NativeAccessibleLabels",
            "NoticeGuideDisplay",
            "NativeLaunchDocuments"),
        seams(GTK4));
    assertInstanceOf(WindowManagerStacking.class, GTK4.stacking());
  }

  /** Only cocoa types with Alt alone; every platform types with none and with AltGr. */
  @Test
  void altTypesOnCocoaOnly() {
    for (PlatformTraits traits : List.of(WIN32, COCOA, GTK3, GTK4)) {
      KeyBehavior keys = traits.keyBehavior();
      assertTrue(keys.types(0), traits.name());
      assertTrue(keys.types(SWT.CTRL | SWT.ALT), traits.name());
      assertFalse(keys.types(SWT.CTRL), traits.name());
      assertEquals(traits == COCOA, keys.types(SWT.ALT), traits.name());
    }
  }

  /**
   * win32 follows an ungestured top-frame link; GTK scrolls to anchors and checks a replaced page;
   * only GTK 4 refuses a browser.
   */
  @Test
  void browserBehaviorPerPlatform() {
    for (PlatformTraits traits : List.of(WIN32, COCOA, GTK3, GTK4)) {
      BrowserBehavior browser = traits.browserBehavior();
      final List<String> followed = new ArrayList<String>();
      boolean taken =
          browser.followUngestured(
              "mock:link",
              new Consumer<String>() {
                @Override
                public void accept(String location) {
                  followed.add(location);
                }
              });
      boolean win32 = traits == WIN32;
      boolean gtk = traits == GTK3 || traits == GTK4;
      assertEquals(win32, taken, traits.name());
      assertEquals(win32 ? List.of("mock:link") : List.of(), followed, traits.name());
      final int[] runs = {0};
      Runnable count =
          new Runnable() {
            @Override
            public void run() {
              runs[0]++;
            }
          };
      browser.scrollToAnchor(count);
      browser.pageChanged(count);
      assertEquals(gtk ? 2 : 0, runs[0], traits.name());
      assertEquals(
          traits == GTK4 ? UiText.MARKDOWN_BROWSER_GTK4.text() : null,
          browser.unavailable(),
          traits.name());
    }
  }

  /** GTK 4 drops menu tool tips, so a reason goes in the label there. */
  @Test
  void onlyGtk4PutsAReasonInTheLabel() {
    String reason = UiText.MENU_ITEM_REASON.format("Mock", "mock reason");
    assertEquals(reason, GTK4.menuBehavior().label("Mock", "mock reason"));
    assertEquals("Mock", GTK4.menuBehavior().label("Mock", null));
    for (PlatformTraits traits : List.of(WIN32, COCOA, GTK3)) {
      assertEquals("Mock", traits.menuBehavior().label("Mock", "mock reason"), traits.name());
    }
  }

  /** GTK shows the faded copy for a disabled icon on a dark background. */
  @Test
  void onlyGtkShowsTheFadedCopy() {
    for (PlatformTraits traits : List.of(WIN32, COCOA, GTK3, GTK4)) {
      final boolean[] faded = {false};
      traits
          .menuBehavior()
          .disabledOnDark(
              null,
              new MenuBehavior.FadedImage() {
                @Override
                public Image get() {
                  faded[0] = true;
                  return null;
                }
              });
      assertEquals(traits == GTK3 || traits == GTK4, faded[0], traits.name());
    }
  }

  @Test
  void onlyGtkReportsAToolkitVersion() {
    String gtk = System.getProperty("org.eclipse.swt.internal.gtk.version");
    assertEquals(gtk, GTK3.toolkitVersion());
    assertEquals(gtk, GTK4.toolkitVersion());
    assertNull(WIN32.toolkitVersion());
    assertNull(COCOA.toolkitVersion());
  }

  @Test
  void gtk3StacksOnX11AndPositionsWindowsExceptUnderWayland() {
    String key = PlatformTraits.GDK_BACKEND;
    String saved = System.getProperty(key);
    try {
      System.setProperty(key, "wayland");
      assertFalse(((NativeWindowPlacement) GTK3.windowPlacement()).positioned());
      assertInstanceOf(WindowManagerStacking.class, GTK3.stacking());
      System.setProperty(key, "x11");
      assertTrue(((NativeWindowPlacement) GTK3.windowPlacement()).positioned());
      assertInstanceOf(GtkX11Stacking.class, GTK3.stacking());
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
    assertEquals(SWT.getPlatform(), PlatformTraits.PLATFORM.name());
    boolean gtk4 = "1".equals(System.getenv("SWT_GTK4"));
    assertEquals(
        PlatformTraits.of(SWT.getPlatform(), gtk4).getClass(), PlatformTraits.PLATFORM.getClass());
  }

  @Test
  void gtk4IsReadOnlyOnGtk() {
    assertInstanceOf(Gtk4Traits.class, GTK4);
    assertInstanceOf(Gtk3Traits.class, GTK3);
    assertInstanceOf(Win32Traits.class, PlatformTraits.of("win32", true));
    assertInstanceOf(CocoaTraits.class, PlatformTraits.of("cocoa", true));
  }

  /**
   * Behavior that differs by platform sits behind a seam: every boolean {@code PlatformTraits}
   * method is a value trait. A new boolean that switches behavior fails here and in {@code
   * ArchitectureTest}.
   */
  @Test
  void noTraitSwitchesBehavior() {
    for (Method method : PlatformTraits.class.getMethods()) {
      if (method.getReturnType() == boolean.class) {
        assertTrue(BOOLEAN_VALUES.contains(method.getName()), method.getName());
      }
    }
  }

  /** On GTK 4 the SWT internals that keep a tool item's icon size resolve. */
  @Test
  void gtk4IconSizingMembersResolve() {
    assumeTrue(RunningPlatform.GTK4, "GTK 4 only");
    assertTrue(Gtk4ToolItemSizing.resolved());
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
