package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.controller.WindowSet;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wheel over the editor: alone it scrolls, with Ctrl (Command on macOS) it zooms through the
 * zoom commands, and Reset Zoom returns to the Preferences size. Events go to the text through
 * {@code notifyListeners}.
 */
@Tag("swt")
class WheelZoomTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private WindowSet set;
  private MainWindow window;
  private ApplicationController controller;
  private StyledText text;

  @BeforeEach
  void open() throws Exception {
    display = new Display();
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    set =
        new WindowSet(
            workers,
            new SwtUiDispatcher(display),
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    window = new MainWindow(display, Palette.LIGHT);
    controller = new ApplicationController(set, window);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 700, 500), null);
    window.open();
    StringBuilder lines = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      lines.append("line ").append(i).append('\n');
    }
    Path file =
        Files.writeString(temp.resolve("long.txt"), lines.toString(), StandardCharsets.US_ASCII);
    controller.documents().open(List.of(file));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return controller.documents().loading() == 0
                    && window.tabs().getItemCount() == 1
                    && text() != null
                    && text().getLineCount() > 400;
              }
            }));
    text = text();
  }

  @AfterEach
  void close() {
    set.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertTrue(failures.isEmpty(), failures.toString());
  }

  @Test
  void theWheelScrollsAndWithTheModifierZooms() {
    int base = window.defaultFontSize();
    wheel(0, -3);
    assertEquals(3, text.getTopIndex());
    assertEquals(0, controller.fontSize());

    wheel(SWT.MOD1, 3);
    assertEquals(3, text.getTopIndex());
    assertEquals(base + 1, controller.fontSize());
    awaitFontHeight(base + 1);
    wheel(SWT.MOD1, -3);
    wheel(SWT.MOD1, -3);
    assertEquals(base - 1, controller.fontSize());

    controller.execute(CommandId.ZOOM_RESET);
    assertEquals(0, controller.fontSize());
    awaitFontHeight(base);
  }

  @Test
  void resetZoomReturnsToThePreferencesSize() {
    Preferences current = controller.preferences();
    set.settings().set(current.withOptions(current.options().withFontSize(14)));
    wheel(SWT.MOD1, 1);
    wheel(SWT.MOD1, 1);
    assertEquals(16, controller.fontSize());
    awaitFontHeight(16);
    controller.execute(CommandId.ZOOM_RESET);
    assertEquals(14, controller.fontSize());
    awaitFontHeight(14);
  }

  private void wheel(int stateMask, int count) {
    Event event = new Event();
    event.count = count;
    event.stateMask = stateMask;
    event.x = 5;
    event.y = 5;
    text.notifyListeners(SWT.MouseVerticalWheel, event);
  }

  private void awaitFontHeight(final int points) {
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return text.getFont().getFontData()[0].getHeight() == points;
              }
            }),
        "font " + text.getFont().getFontData()[0].getHeight() + ", expected " + points);
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private StyledText text() {
    if (window.tabs().getItemCount() == 0 || window.tabs().getSelection() == null) {
      return null;
    }
    List<Control> pending = new ArrayList<Control>();
    pending.add(window.tabs().getSelection().getControl());
    while (!pending.isEmpty()) {
      Control control = pending.remove(pending.size() - 1);
      if (control instanceof StyledText found) {
        return found;
      }
      if (control instanceof Composite composite) {
        pending.addAll(List.of(composite.getChildren()));
      }
    }
    return null;
  }
}
