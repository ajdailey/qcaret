package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.ChangeChoice;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The external-change prompt and the comparison window on a real display: a dirty buffer's prompt
 * after window activation, Compare opening the file beside the buffer, difference navigation,
 * synchronized scrolling, a theme switch recoloring the window, and, with {@code
 * -Dqcaret.screenshots=<dir>}, the prompt and the window as PNG.
 */
@Tag("swt")
class CompareSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Path file;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

  /** Mock settings, 80 lines; {@code edited} rewrites three of them and adds one. */
  private static String text(boolean edited) {
    StringBuilder text = new StringBuilder("# Mock service settings\n");
    for (int i = 1; i < 80; i++) {
      if (edited && i == 12) {
        text.append("mock.retries = 5\n");
      } else if (edited && i == 40) {
        text.append("mock.timeout = 90\n").append("mock.backoff = linear\n");
      } else if (edited && i == 66) {
        continue;
      } else {
        text.append("mock.key").append(i).append(" = value ").append(i).append('\n');
      }
    }
    return text.toString();
  }

  @BeforeEach
  void open() throws Exception {
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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 900, 600), null);
    window.open();
    file = Files.writeString(temp.resolve("service.conf"), text(false), StandardCharsets.UTF_8);
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        },
        "opened");
    drain();
  }

  @AfterEach
  void close() {
    try {
      controller.quit();
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return window.shell().isDisposed();
            }
          },
          BOUND);
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  /** Edits the buffer, changes the file and activates the window until the prompt shows. */
  private ChangeDialog prompt() throws Exception {
    controller.documents().activeEditor().goTo(0, 0);
    controller.documents().activeEditor().type("# Local note\n");
    Files.writeString(file, text(true), StandardCharsets.UTF_8);
    controller.windowActivated();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.changeDialog() != null;
          }
        },
        "prompt");
    drain();
    return window.changeDialog();
  }

  private DiffWindow compare(ChangeDialog dialog) {
    dialog.press(ChangeChoice.COMPARE);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.diffWindows().size() == 1;
          }
        },
        "comparison window");
    drain();
    return window.diffWindows().get(0);
  }

  @Test
  void compareShowsTheFileBesideTheBufferAndNavigates() throws Exception {
    ChangeDialog dialog = prompt();
    DiffWindow diff = compare(dialog);
    assertFalse(dialog.shell().isDisposed());
    StyledText left = diff.left();
    StyledText right = diff.right();
    assertEquals(left.getLineCount(), right.getLineCount());
    assertEquals("", left.getLine(0));
    assertEquals("# Mock service settings", left.getLine(1));
    assertEquals("# Local note", right.getLine(0));
    assertEquals(0, diff.current());
    diff.next();
    assertEquals(1, diff.current());
    Event f7 = new Event();
    f7.keyCode = SWT.F7;
    f7.stateMask = SWT.SHIFT;
    left.notifyListeners(SWT.KeyDown, f7);
    assertEquals(0, diff.current());
    diff.previous();
    assertEquals(3, diff.current());
    left.setTopIndex(30);
    left.getVerticalBar().notifyListeners(SWT.Selection, new Event());
    drain();
    assertEquals(30, right.getTopIndex());
    controller.execute(CommandId.THEME_DARK);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.palette().equals(Palette.DARK);
          }
        },
        "dark");
    Rgb dark = Palette.DARK.background();
    assertEquals(new Color(dark.red(), dark.green(), dark.blue()), right.getBackground());
    diff.shell().dispose();
    dialog.press(ChangeChoice.KEEP);
    assertTrue(dialog.shell().isDisposed());
    assertTrue(controller.documents().activeEditor().document().isDirty());
  }

  @Test
  void reloadFromThePromptLoadsTheFileKeepingTheTopLine() throws Exception {
    ChangeDialog dialog = prompt();
    StyledText text = window.activeEditor().text();
    text.setTopIndex(30);
    drain();
    dialog.press(ChangeChoice.RELOAD);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return controller
                .documents()
                .activeEditor()
                .document()
                .text()
                .toString()
                .equals(text(true));
          }
        },
        "reloaded");
    assertFalse(controller.documents().activeEditor().document().isDirty());
    drain();
    assertEquals(30, text.getTopIndex());
  }

  @Test
  void screenshotsWhenAsked() throws Exception {
    String dir = System.getProperty("qcaret.screenshots");
    assumeTrue(dir != null, "qcaret.screenshots not set");
    ChangeDialog dialog = prompt();
    capture(dir, "change-prompt");
    DiffWindow diff = compare(dialog);
    diff.shell().setBounds(40, 40, 1000, 640);
    diff.next();
    diff.next();
    diff.shell().forceActive();
    capture(dir, "diff-view");
  }

  private void pause(int millis) {
    final long until = System.nanoTime() + millis * 1_000_000L;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
  }

  /** Copies the screen to a PNG. */
  private void capture(String dir, String name) {
    pause(700);
    Rectangle bounds = display.getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    ImageLoader loader = new ImageLoader();
    loader.data = new ImageData[] {image.getImageData()};
    loader.save(Path.of(dir, name + ".png").toString(), SWT.IMAGE_PNG);
    image.dispose();
  }
}
