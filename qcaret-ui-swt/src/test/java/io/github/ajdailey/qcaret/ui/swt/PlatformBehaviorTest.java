package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.ui.swt.editor.CaretInk;
import io.github.ajdailey.qcaret.ui.swt.platform.CaretBlend;
import io.github.ajdailey.qcaret.ui.swt.platform.ChromeColors;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogColors;
import io.github.ajdailey.qcaret.ui.swt.platform.MenuBehavior;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What each view-layer platform branch does on the running platform, with the expected value worked
 * out here from {@link SWT#getPlatform} and {@code SWT_GTK4}, so a run on each CI platform pins its
 * own row.
 */
@Tag("swt")
class PlatformBehaviorTest {
  private static final String PLATFORM = SWT.getPlatform();
  private static final boolean WIN32 = "win32".equals(PLATFORM);
  private static final boolean COCOA = "cocoa".equals(PLATFORM);
  private static final boolean GTK = "gtk".equals(PLATFORM);
  private static final boolean GTK4 = GTK && "1".equals(System.getenv("SWT_GTK4"));

  private static final BooleanSupplier LIGHT_SYSTEM =
      new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
          return false;
        }
      };

  private Display display;

  @BeforeEach
  void open() {
    display = new Display();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void monospaceDefaults() {
    assertEquals(WIN32 ? "Consolas" : COCOA ? "Menlo" : "Monospace", Dialogs.monospaceName());
    assertEquals(COCOA ? 12 : 10, Dialogs.monospaceSize());
  }

  @Test
  void caretBlend() {
    CaretBlend expected = GTK4 ? CaretBlend.OVER : WIN32 ? CaretBlend.XOR : CaretBlend.DIFFERENCE;
    assertEquals(expected, CaretInk.platform());
  }

  @Test
  void applicationMenu() {
    assertEquals(COCOA, !MenuBehavior.PLATFORM.systemCommands(display).isEmpty());
  }

  @Test
  void mainWindowAnswers() {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    try {
      assertTrue(
          window.toolkitDescription().startsWith("SWT " + SWT.getVersion() + " " + PLATFORM),
          window.toolkitDescription());
    } finally {
      window.dispose();
    }
  }

  @Test
  void restartForNativeTheme() {
    SwtThemeService service =
        new SwtThemeService(
            display, false, LIGHT_SYSTEM, PlatformTraits.PLATFORM.themeFollow(display));
    assertTrue(service.showsNow(false));
    assertEquals(!WIN32, service.showsNow(true));
  }

  @Test
  void systemStartSetsNativeDarkWhereControlsThemeAtCreation() {
    boolean systemDark = Display.isSystemDarkTheme();
    try {
      for (String platform : new String[] {"win32", "cocoa", "gtk"}) {
        for (boolean gtk4 : new boolean[] {false, true}) {
          PlatformTraits traits = PlatformTraits.of(platform, gtk4);
          String name = platform + " " + gtk4;
          assertEquals(
              "win32".equals(platform) && systemDark, start(traits, ThemeMode.SYSTEM), name);
          assertEquals(!"cocoa".equals(platform), start(traits, ThemeMode.DARK), name);
          assertFalse(start(traits, ThemeMode.LIGHT), name);
        }
      }
    } finally {
      display.setDarkThemePreferred(false);
    }
  }

  /** Opens the theme service on a new Display for a platform and returns its native setting. */
  private boolean start(PlatformTraits traits, ThemeMode mode) {
    display.setDarkThemePreferred(false);
    display.dispose();
    display = new Display();
    SwtThemeService.open(display, mode, traits.themeFollow(display));
    return SwtThemeService.nativeDark(display);
  }

  @Test
  void dialogAndChromeColors() {
    assertEquals(
        WIN32 ? "Win32DialogColors" : "NativeDialogColors",
        DialogColors.platform().getClass().getSimpleName());
    ChromeColors chrome = PlatformTraits.PLATFORM.chromeColors(display);
    try {
      assertEquals(
          WIN32 ? "Win32ChromeColors" : GTK ? "GtkChromeColors" : "NativeChromeColors",
          chrome.getClass().getSimpleName());
    } finally {
      chrome.dispose();
    }
  }

  @Test
  void menuInkIsFixedOnWin32() {
    Shell shell = new Shell(display);
    ToolBar bar = new ToolBar(shell, SWT.FLAT);
    bar.setBackground(new Color(240, 240, 240));
    MenuIcons icons = new MenuIcons(bar);
    bar.setBackground(new Color(30, 30, 30));
    icons.reink();
    assertEquals(!WIN32, icons.icons().darkBackground());
  }

  @Test
  void disabledIconOnDarkIsFadedOnGtk() {
    Shell shell = new Shell(display);
    ToolBar bar = new ToolBar(shell, SWT.FLAT);
    ToolIcons icons = new ToolIcons(bar, true);
    PlatformTraits.PLATFORM.chromeColors(display).add(icons);
    ToolItem item = new ToolItem(bar, SWT.PUSH);
    icons.apply(item, ToolIcons.SAVE);
    item.setEnabled(false);
    icons.showState(item);
    Image plain = icons.get(ToolIcons.SAVE);
    assertEquals(GTK, item.getImage() != plain);
  }
}
