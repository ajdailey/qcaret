package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.Contrast;
import io.github.ajdailey.qcaret.ui.swt.platform.DialogColors;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link DialogColors}: the win32 class's colors per control class, read back through public API on
 * any platform, and native dialog colors elsewhere.
 */
@Tag("swt")
class DialogColorsTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final DialogColors WIN32 = PlatformTraits.of("win32", false).dialogColors();

  /** Dialog body: the dark title bar's color, measured on {@code windows-2025}. */
  private static final Color WINDOW = new Color(0x20, 0x20, 0x20);

  /** Text fields, lists, tables, trees and table headers. */
  private static final Color FIELD = new Color(0x2b, 0x2b, 0x2b);

  private static final Color TEXT = new Color(0xff, 0xff, 0xff);

  private Display display;

  @AfterEach
  void close() {
    if (display != null) {
      display.setDarkThemePreferred(false);
      display.dispose();
    }
  }

  private Shell owner(ThemeMode mode) {
    display = new Display();
    SwtThemeService.open(display, mode);
    return new Shell(display);
  }

  @Test
  void dialogsKeepNativeColorsOffWin32() {
    assumeFalse("win32".equals(SWT.getPlatform()), "win32 colors its dialogs");
    Shell owner = owner(ThemeMode.DARK);
    assertSame(PlatformTraits.of("gtk", false).dialogColors(), DialogColors.platform());
    Shell plain = new Shell(owner, SWT.DIALOG_TRIM);
    Shell dialog = Dialogs.shell(owner, "Dialog", true);
    Label label = new Label(dialog, SWT.NONE);
    assertEquals(plain.getBackground().getRGB(), dialog.getBackground().getRGB());
    assertEquals(
        new Label(plain, SWT.NONE).getForeground().getRGB(), label.getForeground().getRGB());
    assertFalse(DialogColors.platform().colored(label));
    assertEquals(owner.getBackground().getRGB(), Dialogs.background(owner).getRGB());
  }

  /**
   * A read-only field's hint in a dark dialog is readable: the native cue drew {@code #6d6d6d} on
   * the field, 2.74:1 (Preferences, Default workspace folder; OBERON G1).
   */
  @Test
  void win32DrawsAReadOnlyFieldsHintReadableUnderADarkStart() {
    Shell owner = owner(ThemeMode.DARK);
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
    WIN32.apply(dialog);
    Text folder = new Text(dialog, SWT.SINGLE | SWT.BORDER | SWT.READ_ONLY);
    FieldHint hint = new FieldHint(folder);
    hint.setHint("mock none");
    dialog.open();
    assertNotNull(hint.color());
    double ratio = Contrast.ratio(hint.color().getRGB(), FIELD.getRGB());
    assertTrue(ratio >= 4.5, "hint " + ratio);
  }

  @Test
  void win32ColorsEachControlClassUnderADarkStart() {
    // cocoa reads a Shell background back as the system color whatever was set.
    assumeFalse("cocoa".equals(SWT.getPlatform()), "cocoa reads back system colors");
    Shell owner = owner(ThemeMode.DARK);
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
    WIN32.apply(dialog);
    Composite composite = new Composite(dialog, SWT.NONE);
    Label label = new Label(composite, SWT.NONE);
    Link link = new Link(dialog, SWT.NONE);
    Button check = new Button(dialog, SWT.CHECK);
    Text text = new Text(dialog, SWT.BORDER);
    Combo dropDown = new Combo(dialog, SWT.DROP_DOWN);
    Combo readOnly = new Combo(dialog, SWT.READ_ONLY);
    List list = new List(dialog, SWT.BORDER);
    Table table = new Table(dialog, SWT.BORDER);
    Tree tree = new Tree(dialog, SWT.BORDER);
    Spinner spinner = new Spinner(dialog, SWT.BORDER);
    StyledText palette = new StyledText(dialog, SWT.NONE);
    palette.setData(EditorPane.PALETTE_COLORED, Boolean.TRUE);
    Color paletteBackground = new Color(0x11, 0x22, 0x33);
    palette.setBackground(paletteBackground);
    dialog.open();
    for (Control surface : new Control[] {dialog, composite, label, link, check}) {
      assertColors(surface, WINDOW);
    }
    for (Control field : new Control[] {text, dropDown, list, table, tree, spinner}) {
      assertColors(field, FIELD);
    }
    assertEquals(FIELD.getRGB(), table.getHeaderBackground().getRGB());
    assertEquals(TEXT.getRGB(), table.getHeaderForeground().getRGB());
    assertNotEquals(FIELD.getRGB(), readOnly.getBackground().getRGB());
    assertEquals(paletteBackground.getRGB(), palette.getBackground().getRGB());
    RGB linkColor = link.getLinkForeground().getRGB();
    assertTrue(SwtThemeService.contrast(linkColor, WINDOW.getRGB()) >= 4.5);
    assertTrue(WIN32.colored(label));
    assertFalse(WIN32.colored(owner));
    assertEquals(WINDOW.getRGB(), WIN32.background(owner).getRGB());
  }

  /** Quick Open and the command palette fill their tables while open. */
  @Test
  void win32ColorsAControlCreatedAfterTheDialogShows() {
    // cocoa reads a Shell background back as the system color whatever was set.
    assumeFalse("cocoa".equals(SWT.getPlatform()), "cocoa reads back system colors");
    Shell dialog = new Shell(owner(ThemeMode.DARK), SWT.DIALOG_TRIM);
    WIN32.apply(dialog);
    dialog.open();
    final Table table = new Table(dialog, SWT.BORDER);
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return FIELD.getRGB().equals(table.getBackground().getRGB());
              }
            },
            BOUND));
    assertEquals(TEXT.getRGB(), table.getForeground().getRGB());
  }

  @Test
  void win32LeavesALightStartAlone() {
    Shell owner = owner(ThemeMode.LIGHT);
    Shell plain = new Shell(owner, SWT.DIALOG_TRIM);
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM);
    WIN32.apply(dialog);
    Text text = new Text(dialog, SWT.BORDER);
    dialog.open();
    assertEquals(plain.getBackground().getRGB(), dialog.getBackground().getRGB());
    assertEquals(
        new Text(plain, SWT.BORDER).getBackground().getRGB(), text.getBackground().getRGB());
    assertFalse(WIN32.colored(text));
    assertEquals(owner.getBackground().getRGB(), WIN32.background(owner).getRGB());
  }

  private static void assertColors(Control control, Color background) {
    String name = control.getClass().getSimpleName();
    assertEquals(background.getRGB(), control.getBackground().getRGB(), name + " background");
    assertEquals(TEXT.getRGB(), control.getForeground().getRGB(), name + " foreground");
  }
}
