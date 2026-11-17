package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** {@link BrowserColors} with each platform's traits, on any platform. */
@Tag("swt")
class BrowserColorsTest {
  private Display display;
  private Shell shell;
  private Tree tree;
  private Text field;

  @BeforeEach
  void create() {
    display = new Display();
    shell = new Shell(display);
    tree = new Tree(shell, SWT.NONE);
    tree.setHeaderVisible(true);
    field = new Text(shell, SWT.SINGLE);
  }

  @AfterEach
  void dispose() {
    display.dispose();
  }

  private List<RGB> colors() {
    return List.of(
        tree.getBackground().getRGB(),
        tree.getForeground().getRGB(),
        tree.getHeaderBackground().getRGB(),
        tree.getHeaderForeground().getRGB(),
        field.getBackground().getRGB(),
        field.getForeground().getRGB());
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  @Test
  void aDarkThemeOnWin32ColorsTheTreeItsHeaderAndTheFields() {
    BrowserColors.apply(PlatformTraits.of("win32", false), true, false, tree, field);
    RGB background = rgb(Palette.DARK.background());
    RGB text = rgb(Palette.DARK.text());
    assertEquals(
        List.of(background, text, rgb(Palette.DARK.gutter()), text, background, text), colors());
  }

  @Test
  void aLightThemeRestoresTheSystemColors() {
    List<RGB> system = colors();
    PlatformTraits win32 = PlatformTraits.of("win32", false);
    BrowserColors.apply(win32, true, false, tree, field);
    BrowserColors.apply(win32, false, false, tree, field);
    assertEquals(system, colors());
  }

  @Test
  void theFieldsStayDarkAfterADarkStartWhileTheTreeFollowsTheTheme() {
    List<RGB> system = colors();
    PlatformTraits win32 = PlatformTraits.of("win32", false);
    RGB background = rgb(Palette.DARK.background());
    RGB text = rgb(Palette.DARK.text());
    List<RGB> darkTree = List.of(background, text, rgb(Palette.DARK.gutter()), text);
    for (boolean dark : new boolean[] {true, false, true, false}) {
      BrowserColors.apply(win32, dark, true, tree, field);
      assertEquals(dark ? darkTree : system.subList(0, 4), colors().subList(0, 4));
      assertEquals(List.of(background, text), colors().subList(4, 6));
    }
  }

  @Test
  void otherPlatformsKeepTheSystemColorsUnderADarkTheme() {
    List<RGB> system = colors();
    for (boolean nativeDark : new boolean[] {false, true}) {
      BrowserColors.apply(PlatformTraits.of("cocoa", false), true, nativeDark, tree, field);
      BrowserColors.apply(PlatformTraits.of("gtk", false), true, nativeDark, tree, field);
      BrowserColors.apply(PlatformTraits.of("gtk", true), true, nativeDark, tree, field);
    }
    assertEquals(system, colors());
  }
}
