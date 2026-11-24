package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * On win32 the Workspace Browser's tree, header and fields take {@link Palette#DARK}'s colors under
 * a dark start; the tree and header follow live theme switches to light and back, and on the light
 * side a field holding text stays dark while an empty one takes the system colors. The path field's
 * drawn color is read from a print, since Windows can draw a field under the dark Explorer theme in
 * a color {@code getBackground} does not report. The empty filter field's hint is read from the
 * screen, where its Paint listener's drawing shows.
 */
@Tag("swt")
@EnabledOnOs(OS.WINDOWS)
class Win32DarkBrowserTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @AfterEach
  void close() {
    if (display == null) {
      return;
    }
    try {
      display.setDarkThemePreferred(false);
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private static RGB rgb(Rgb rgb) {
    return new RGB(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Returns the most frequent color inside a control's 3-pixel inset, its drawn background. */
  private RGB drawn(Control control) {
    ImageData data = ControlPrint.of(display, control);
    assertTrue(data != null, "print");
    Map<RGB, Integer> counts = new HashMap<RGB, Integer>();
    RGB most = null;
    for (int y = 3; y < data.height - 3; y++) {
      for (int x = 3; x < data.width - 3; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        Integer count = counts.get(rgb);
        int next = count == null ? 1 : count + 1;
        counts.put(rgb, next);
        if (most == null || next > counts.get(most)) {
          most = rgb;
        }
      }
    }
    return most;
  }

  private void await(final BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private static double luminance(RGB rgb) {
    double sum = 0;
    double[] weights = {0.2126, 0.7152, 0.0722};
    int[] channels = {rgb.red, rgb.green, rgb.blue};
    for (int i = 0; i < 3; i++) {
      double c = channels[i] / 255.0;
      c = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
      sum += weights[i] * c;
    }
    return sum;
  }

  /** Returns the contrast against the dark background of the lightest pixel inside the inset. */
  private double hintContrast(Text field) {
    Point origin = field.toDisplay(0, 0);
    Point size = field.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(display);
    ImageData data;
    try {
      gc.copyArea(image, origin.x, origin.y);
      data = Pixels.read(image, field.getShell());
    } finally {
      gc.dispose();
      image.dispose();
    }
    double lightest = 0;
    for (int y = 3; y < data.height - 3; y++) {
      for (int x = 3; x < data.width - 3; x++) {
        lightest = Math.max(lightest, luminance(data.palette.getRGB(data.getPixel(x, y))));
      }
    }
    return (lightest + 0.05) / (luminance(rgb(Palette.DARK.background())) + 0.05);
  }

  private MainWindow openDark() {
    display = new Display();
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    MainWindow window = new MainWindow(display, Palette.DARK);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(ThemeMode.DARK),
            SwtThemeService.open(display, ThemeMode.DARK),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
    controller.execute(CommandId.WORKSPACE_BROWSER);
    return window;
  }

  @Test
  void theEmptyFilterFieldDrawsItsHintLegiblyUnderDark() {
    MainWindow window = openDark();
    WorkspaceDock dock = window.workspaceDock();
    final Text filter = dock.bar().filter();
    dock.tree().setFocus();
    assertEquals("", filter.getText());
    ScreenProbe.assumeShows(window.shell());
    // The hint paints after the focus change.
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            display.update();
            return hintContrast(filter) >= 4.5;
          }
        },
        BOUND);
    double contrast = hintContrast(filter);
    assertTrue(contrast >= 4.5, "hint contrast " + contrast);
  }

  @Test
  void theBrowserFollowsADarkStartAndLiveSwitches() {
    MainWindow window = openDark();
    final WorkspaceDock dock = window.workspaceDock();
    final Tree tree = dock.tree();
    final RGB dark = rgb(Palette.DARK.background());
    assertEquals(dark, tree.getBackground().getRGB());
    assertEquals(rgb(Palette.DARK.gutter()), tree.getHeaderBackground().getRGB());
    assertEquals(rgb(Palette.DARK.text()), tree.getHeaderForeground().getRGB());
    assertEquals(dark, dock.bar().path().getBackground().getRGB());
    assertEquals(dark, dock.bar().filter().getBackground().getRGB());
    // Windows draws an unfocused field differently from a focused one under the dark theme.
    tree.setFocus();
    assertEquals(dark, drawn(dock.bar().path()));
    // With no workspace folder the path field is empty; the filter field holds text.
    final Text path = dock.bar().path();
    final Text filter = dock.bar().filter();
    assertEquals("", path.getText());
    filter.setText("*.md");
    assertEquals(dark, filter.getBackground().getRGB());
    final RGB system = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND).getRGB();
    final RGB text = rgb(Palette.DARK.text());
    controller.execute(CommandId.THEME_LIGHT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tree.getBackground().getRGB().equals(system);
          }
        },
        "light tree");
    // On the light side an empty field takes the system colors; one holding text stays dark.
    assertEquals(system, path.getBackground().getRGB());
    assertEquals(system, drawn(path));
    assertEquals(dark, filter.getBackground().getRGB());
    assertEquals(text, filter.getForeground().getRGB());
    assertEquals(dark, drawn(filter));
    filter.setFocus();
    assertEquals(dark, drawn(filter));
    tree.setFocus();
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tree.getBackground().getRGB().equals(dark);
          }
        },
        "dark tree again");
    assertEquals(dark, path.getBackground().getRGB());
    assertEquals(dark, drawn(path));
    assertEquals(dark, drawn(filter));
    controller.execute(CommandId.THEME_LIGHT);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return tree.getBackground().getRGB().equals(system);
          }
        },
        "light tree again");
    assertEquals(system, drawn(path));
    assertEquals(dark, drawn(filter));
    assertEquals(text, filter.getForeground().getRGB());
    // Emptied on the light side, the filter field takes the system colors too.
    filter.setText("");
    assertEquals(system, filter.getBackground().getRGB());
  }
}
