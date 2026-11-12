package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.ui.swt.editor.CaretInk;
import io.github.ajdailey.qcaret.ui.swt.platform.CaretBlend;
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
    assertEquals(COCOA, AppMenu.present(display));
  }

  @Test
  void mainWindowAnswers() {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    try {
      assertEquals(WIN32, window.launchesByExtension());
      assertTrue(
          window.toolkitDescription().startsWith("SWT " + SWT.getVersion() + " " + PLATFORM),
          window.toolkitDescription());
    } finally {
      window.dispose();
    }
  }

  @Test
  void restartForNativeTheme() {
    SwtThemeService service = new SwtThemeService(display, false, LIGHT_SYSTEM);
    assertTrue(service.showsNow(false));
    assertEquals(!WIN32, service.showsNow(true));
  }

  @Test
  void dialogColors() {
    assertEquals(
        WIN32 ? Win32DialogSurfaces.class : NativeDialogSurfaces.class,
        DialogSurfaces.platform().getClass());
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
    ToolItem item = new ToolItem(bar, SWT.PUSH);
    icons.apply(item, ToolIcons.SAVE);
    item.setEnabled(false);
    icons.showState(item);
    Image plain = icons.get(ToolIcons.SAVE);
    assertEquals(GTK, item.getImage() != plain);
  }
}
