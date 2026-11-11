package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.Appearance;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.editor.ColumnMetrics;
import io.github.ajdailey.qcaret.app.theme.EditorSchemes;
import io.github.ajdailey.qcaret.app.theme.FontFiles;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeColors;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ViewLayout;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The editor font family and theme files on a real window: the Appearance tab, live application to
 * every view, the fallbacks, and column math on rows with bold keywords in two installed families.
 */
@Tag("swt")
class FontFamilyTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final String ROW = "public class Mock { public int value = 42; }";
  private static final int NUMBER_DIGITS = ColumnMetrics.gutterDigits(1);
  private static final int SIZES_SEARCHED = 48;

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac(), SwtKeys.linux()),
            false);
    window.attach(controller);
    controller.loadGrammars();
    window.open();
  }

  @AfterEach
  void close() {
    try {
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  /** Returns the installed monospace families the toolkit accepts, in scan order. */
  private List<String> families() {
    List<String> usable = new ArrayList<String>();
    List<Path> folders =
        FontFiles.directories(
            System.getProperty("os.name"), System.getenv(), System.getProperty("user.home"));
    for (String family : FontFiles.monospaceFamilies(folders)) {
      if (EditorFonts.family(display, family).equals(family)) {
        usable.add(family);
      }
    }
    return usable;
  }

  private String editorFamily() {
    return window.editorFont().getFontData()[0].getName();
  }

  private EditorPane javaRow() throws Exception {
    Path file = Files.writeString(temp.resolve("Mock.java"), ROW + "\n", StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            EditorPane pane = window.activeEditor();
            return pane != null && bold(pane);
          }
        },
        "keyword styled");
    return window.activeEditor();
  }

  private static boolean bold(EditorPane pane) {
    for (StyleRange style : pane.rowStyles(0)) {
      if ((style.fontStyle & SWT.BOLD) != 0) {
        return true;
      }
    }
    return false;
  }

  /** Checks that the x of every char of the row maps back to its column. */
  private static void assertColumnsMatch(EditorPane pane, String family) {
    StyledText text = pane.text();
    ViewLayout layout = pane.layout();
    for (int column = 0; column <= ROW.length(); column++) {
      int x = text.getLocationAtOffset(column).x;
      assertEquals(column, layout.columnAt(0, x), family + " column " + column);
    }
  }

  @Test
  void boldKeywordRowsKeepTheirColumnsInTwoFamilies() throws Exception {
    List<String> families = families();
    assumeTrue(families.size() >= 2, "two monospace families installed");
    EditorPane pane = javaRow();
    assertColumnsMatch(pane, "default");
    for (String family : families.subList(0, 2)) {
      window.setFontFamily(family);
      assertEquals(family, editorFamily());
      assertTrue(bold(pane));
      assertColumnsMatch(pane, family);
      assertTrue(pane.metrics().advance() > 0);
    }
  }

  private ColumnMetrics measured(EditorPane pane, String family, int points) {
    Font font = new Font(display, family, points, SWT.NORMAL);
    GC gc = new GC(pane.text());
    try {
      gc.setFont(font);
      return ColumnMetrics.fromSample(gc.textExtent(ColumnMetrics.sample()).x);
    } finally {
      gc.dispose();
      font.dispose();
    }
  }

  /**
   * Searches for a size where two families' number columns round apart: only there does a gutter
   * left over from the old family differ from a measured one. The macOS families all measure near
   * 0.60 em, so most sizes give every family one gutter.
   */
  @Test
  void aChangedFamilyMeasuresTheGutterAgain() throws Exception {
    List<String> families = families();
    assumeTrue(families.size() >= 2, "two monospace families installed");
    EditorPane pane = javaRow();
    String first = families.get(0);
    int smallest = Dialogs.monospaceSize();
    for (int points = smallest; points < smallest + SIZES_SEARCHED; points++) {
      ColumnMetrics from = measured(pane, first, points);
      for (String family : families.subList(1, families.size())) {
        ColumnMetrics to = measured(pane, family, points);
        if (from.x(NUMBER_DIGITS) != to.x(NUMBER_DIGITS)) {
          assertGutterFollows(pane, points, first, from, family, to);
          return;
        }
      }
    }
    assumeTrue(false, "two families with different gutters");
  }

  private void assertGutterFollows(
      EditorPane pane,
      int points,
      String first,
      ColumnMetrics from,
      String second,
      ColumnMetrics to) {
    String pair = first + " to " + second + " at " + points;
    window.setFontSize(points);
    window.setFontFamily(first);
    assertEquals(from, pane.metrics(), pair);
    int fold = pane.foldColumn();
    int gap = pane.text().getLeftMargin() - pane.gutterWidth();
    window.setFontFamily(second);
    assertEquals(to, pane.metrics(), pair);
    assertEquals(to.x(NUMBER_DIGITS) - from.x(NUMBER_DIGITS), pane.foldColumn() - fold, pair);
    assertEquals(gap, pane.text().getLeftMargin() - pane.gutterWidth(), pair);
  }

  @Test
  void aMissingFamilyFallsBackToThePlatformFont() {
    window.setFontFamily("QCaret Mock Absent");
    assertEquals(Dialogs.monospaceName(), editorFamily());
  }

  @Test
  void aProportionalFamilyFallsBackToThePlatformFont() {
    String proportional = null;
    for (FontData data : display.getFontList(null, true)) {
      if (!EditorFonts.monospace(display, data.getName())) {
        proportional = data.getName();
        break;
      }
    }
    assumeTrue(proportional != null, "a proportional family installed");
    assertEquals(Dialogs.monospaceName(), EditorFonts.family(display, proportional));
    window.setFontFamily(proportional);
    assertEquals(Dialogs.monospaceName(), editorFamily());
  }

  @Test
  void anEmptyFamilyIsThePlatformFont() {
    List<String> families = families();
    assumeTrue(!families.isEmpty(), "a monospace family installed");
    window.setFontFamily(families.get(0));
    window.setFontFamily("");
    assertEquals(Dialogs.monospaceName(), editorFamily());
  }

  @Test
  void theFamilyKeepsTheZoomAndZoomKeepsTheFamily() {
    List<String> families = families();
    assumeTrue(!families.isEmpty(), "a monospace family installed");
    window.setFontSize(17);
    window.setFontFamily(families.get(0));
    assertEquals(17, window.editorFont().getFontData()[0].getHeight());
    window.setFontSize(18);
    assertEquals(families.get(0), editorFamily());
  }

  @Test
  void openTextAndComparisonViewsFollowTheFamily() throws Exception {
    List<String> families = families();
    assumeTrue(!families.isEmpty(), "a monospace family installed");
    String family = families.get(0);
    window.showLog("mock log");
    TextWindow log = window.logWindow();
    assertNotEquals(family, log.font().getFontData()[0].getName());
    window.setFontFamily(family);
    assertEquals(family, log.font().getFontData()[0].getName());
    assertEquals(Dialogs.monospaceSize(), log.font().getFontData()[0].getHeight());
    log.shell().dispose();
    window.showLog("mock log");
    assertEquals(family, window.logWindow().font().getFontData()[0].getName());
    window.logWindow().shell().dispose();
  }

  @Test
  void aFamilyTheToolkitDoesNotListIsNotOffered() {
    List<String> families = new ArrayList<String>(families());
    assumeTrue(!families.isEmpty(), "a monospace family installed");
    String family = families.get(0);
    assertEquals(
        List.of(family), EditorFonts.listed(display, List.of("QCaret Mock Absent", family)));
  }

  @Test
  void thePreferencesDialogListsChoicesWithoutHoldingItsOpenAndAppliesThem() throws Exception {
    List<String> families = families();
    assumeTrue(!families.isEmpty(), "a monospace family installed");
    Path themes = Files.createDirectories(temp.resolve(ThemeColors.DIRECTORY));
    Files.writeString(themes.resolve("harbor.theme"), "light.background = #102030\n");
    controller.execute(CommandId.PREFERENCES);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            PreferencesDialog dialog = window.preferencesDialog();
            return dialog != null && dialog.fontFamily().getItemCount() > 1;
          }
        },
        "choices listed");
    final PreferencesDialog dialog = window.preferencesDialog();
    assertEquals(0, dialog.fontFamily().getSelectionIndex());
    Combo colors = dialog.editorColors();
    int file = EditorSchemes.names().size();
    assertEquals(file + 1, colors.getItemCount());
    assertEquals("harbor", colors.getItem(file));
    colors.select(file);
    dialog.fontFamily().select(dialog.fontFamily().indexOf(families.get(0)));
    assertTrue(dialog.apply());
    assertEquals(families.get(0), editorFamily());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.palette().background().equals(Rgb.of(0x102030));
          }
        },
        "theme file applied");
    dialog.restoreDefaults();
    assertEquals(0, dialog.editorColors().getSelectionIndex());
    assertTrue(dialog.apply());
    assertEquals(Dialogs.monospaceName(), editorFamily());
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.palette().equals(Palette.LIGHT);
          }
        },
        "built-in colors back");
    dialog.shell().dispose();
  }

  @Test
  void aSavedFamilyTheScanLacksStaysSelected() {
    controller
        .settings()
        .set(
            controller
                .settings()
                .current()
                .withAppearance(new Appearance("", "QCaret Mock Absent", EditorSchemes.FOLLOW)));
    assertEquals(Dialogs.monospaceName(), editorFamily());
    controller.execute(CommandId.PREFERENCES);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            PreferencesDialog dialog = window.preferencesDialog();
            return dialog != null && dialog.fontFamily().getItemCount() > 1;
          }
        },
        "dialog open");
    PreferencesDialog dialog = window.preferencesDialog();
    assertEquals("QCaret Mock Absent", dialog.fontFamily().getText());
    dialog.shell().dispose();
  }
}
