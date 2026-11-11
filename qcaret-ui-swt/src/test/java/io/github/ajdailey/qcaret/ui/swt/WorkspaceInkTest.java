package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Workspace Browser's icon tool bars take the main toolbar's ink when QCaret starts dark, after
 * a theme switch either way and after the browser is hidden and shown again. GTK only: elsewhere
 * the ink comes from {@code getBackground}, which needs no drawn pixel and no event-loop turn.
 */
@Tag("swt")
class WorkspaceInkTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  @BeforeEach
  void open() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "the GTK ink is read from a drawn pixel");
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
    SwtThemeService system = SwtThemeService.open(display, ThemeMode.DARK);
    window = new MainWindow(display, Palette.DARK);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp)),
            Preferences.DEFAULTS.withTheme(ThemeMode.DARK),
            system,
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
    window.open();
  }

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

  @Test
  void theBrowserButtonsTakeTheToolbarsInk() {
    assumeTrue(
        EventLoop.runUntil(display, toolbarInk(true), BOUND),
        "the GTK theme draws no dark toolbar: it has no dark variant");
    controller.execute(CommandId.WORKSPACE_BROWSER);
    assertBrowserInk(true, "at a dark start");

    controller.execute(CommandId.THEME_LIGHT);
    assertTrue(EventLoop.runUntil(display, toolbarInk(false), BOUND), "light toolbar");
    assertBrowserInk(false, "after a switch to light");

    controller.execute(CommandId.THEME_DARK);
    assertTrue(EventLoop.runUntil(display, toolbarInk(true), BOUND), "dark toolbar");
    assertBrowserInk(true, "after a switch to dark");

    controller.execute(CommandId.WORKSPACE_BROWSER);
    controller.execute(CommandId.WORKSPACE_BROWSER);
    assertBrowserInk(true, "after hiding and showing the browser");
  }

  /** Runs the event loop until every browser button shows the ink, then checks each one. */
  private void assertBrowserInk(final boolean light, String when) {
    final List<ToolItem> buttons = new ArrayList<ToolItem>();
    collect(window.workspaceDock().control(), buttons);
    assertFalse(buttons.isEmpty(), "no icon items");
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            for (ToolItem button : buttons) {
              if (lightInk(button.getImage()) != light) {
                return false;
              }
            }
            return true;
          }
        },
        BOUND);
    for (ToolItem button : buttons) {
      assertTrue(
          lightInk(button.getImage()) == light,
          button.getToolTipText() + (light ? " has dark ink " : " has light ink ") + when);
    }
  }

  private BooleanSupplier toolbarInk(final boolean light) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        for (ToolItem item : window.mainToolBar().bar().getItems()) {
          if (item.getImage() != null && item.getEnabled()) {
            return lightInk(item.getImage()) == light;
          }
        }
        return false;
      }
    };
  }

  private static void collect(Composite parent, List<ToolItem> buttons) {
    for (Control child : parent.getChildren()) {
      if (child instanceof ToolBar bar) {
        for (ToolItem item : bar.getItems()) {
          if (item.getImage() != null) {
            buttons.add(item);
          }
        }
      } else if (child instanceof Composite composite) {
        collect(composite, buttons);
      }
    }
  }

  /** Reports whether the visible pixels of an image average lighter than mid-gray. */
  private static boolean lightInk(Image image) {
    ImageData data = image.getImageData();
    long sum = 0;
    int count = 0;
    for (int y = 0; y < data.height; y++) {
      for (int x = 0; x < data.width; x++) {
        if (data.getAlpha(x, y) > 0) {
          RGB rgb = data.palette.getRGB(data.getPixel(x, y));
          sum += (rgb.red * 299 + rgb.green * 587 + rgb.blue * 114) / 1000;
          count++;
        }
      }
    }
    return count > 0 && sum / count >= 128;
  }
}
