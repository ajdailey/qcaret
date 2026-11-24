package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.ArrayList;
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
  private FieldHint hint;

  @BeforeEach
  void create() {
    display = new Display();
    shell = new Shell(display);
    tree = new Tree(shell, SWT.NONE);
    tree.setHeaderVisible(true);
    field = new Text(shell, SWT.SINGLE);
    hint = new FieldHint(field);
    hint.setHint("filter");
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
    BrowserColors.apply(PlatformTraits.of("win32", false), true, false, tree, hint);
    RGB background = rgb(Palette.DARK.background());
    RGB text = rgb(Palette.DARK.text());
    assertEquals(
        List.of(background, text, rgb(Palette.DARK.gutter()), text, background, text), colors());
  }

  @Test
  void aDarkFieldDrawsItsHintInTheTabTextColorAndALightOneShowsTheNativeMessage() {
    PlatformTraits win32 = PlatformTraits.of("win32", false);
    assertEquals("filter", field.getMessage());
    BrowserColors.apply(win32, true, false, tree, hint);
    assertEquals("", field.getMessage());
    assertEquals(rgb(Palette.DARK.accents().tabText()), hint.color().getRGB());
    BrowserColors.apply(win32, false, false, tree, hint);
    assertEquals("filter", field.getMessage());
    assertNull(hint.color());
    BrowserColors.apply(win32, false, true, tree, hint);
    assertEquals("filter", field.getMessage(), "an empty field after a dark start");
    field.setText("mock");
    BrowserColors.apply(win32, false, true, tree, hint);
    assertEquals("", field.getMessage(), "a field holding text after a dark start");
  }

  @Test
  void aLightThemeRestoresTheSystemColors() {
    List<RGB> system = colors();
    PlatformTraits win32 = PlatformTraits.of("win32", false);
    BrowserColors.apply(win32, true, false, tree, hint);
    BrowserColors.apply(win32, false, false, tree, hint);
    assertEquals(system, colors());
  }

  @Test
  void theFieldsStayDarkAfterADarkStartWhileTheTreeFollowsTheTheme() {
    List<RGB> system = colors();
    PlatformTraits win32 = PlatformTraits.of("win32", false);
    RGB background = rgb(Palette.DARK.background());
    RGB text = rgb(Palette.DARK.text());
    List<RGB> darkTree = List.of(background, text, rgb(Palette.DARK.gutter()), text);
    field.setText("mock");
    for (boolean dark : new boolean[] {true, false, true, false}) {
      BrowserColors.apply(win32, dark, true, tree, hint);
      assertEquals(dark ? darkTree : system.subList(0, 4), colors().subList(0, 4));
      assertEquals(List.of(background, text), colors().subList(4, 6));
    }
  }

  /**
   * The path field, enabled and empty while no workspace folder is set, after a live switch: keyed
   * on the enabled state it stayed dark on win32.
   */
  @Test
  void anEmptyFieldTakesTheSystemColorsOnTheLightSideAfterADarkStart() {
    List<RGB> system = colors();
    PlatformTraits win32 = PlatformTraits.of("win32", false);
    BrowserColors.apply(win32, true, true, tree, hint);
    assertEquals(rgb(Palette.DARK.background()), field.getBackground().getRGB());
    BrowserColors.apply(win32, false, true, tree, hint);
    assertEquals(system.subList(4, 6), colors().subList(4, 6));
    assertNull(hint.color());
    field.setText("mock");
    BrowserColors.apply(win32, false, true, tree, hint);
    assertEquals(rgb(Palette.DARK.background()), field.getBackground().getRGB());
  }

  @Test
  void aFieldIsColoredAgainWhenItTakesTextOrEmpties() {
    final List<String> runs = new ArrayList<String>();
    BrowserColors.follow(
        field,
        new Runnable() {
          @Override
          public void run() {
            runs.add(field.getText());
          }
        });
    field.setText("m");
    field.setText("mo");
    field.setText("");
    field.setText("");
    assertEquals(List.of("m", ""), runs);
  }

  @Test
  void otherPlatformsKeepTheSystemColorsUnderADarkTheme() {
    List<RGB> system = colors();
    for (boolean nativeDark : new boolean[] {false, true}) {
      BrowserColors.apply(PlatformTraits.of("cocoa", false), true, nativeDark, tree, hint);
      BrowserColors.apply(PlatformTraits.of("gtk", false), true, nativeDark, tree, hint);
      BrowserColors.apply(PlatformTraits.of("gtk", true), true, nativeDark, tree, hint);
    }
    assertEquals(system, colors());
    assertEquals("filter", field.getMessage());
  }
}
