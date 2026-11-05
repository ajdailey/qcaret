package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.DeviceData;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tab open and close, theme switches, font zoom, the tab menu, menu syncs, split and unsplit, and
 * the find bar opened, searching and closed return the Display's tracked graphics resources ({@code
 * DeviceData.tracking}) to their count after a warm-up round. Theme switches, zoom and split each
 * rebuild or add a primary caret image. A failure lists each leaked object's allocation stack.
 *
 * <p>System cursors are left out: the Display owns them, and on each GTK settings change (a theme
 * switch) it moves its cached cursors to a list it disposes with itself, so the next {@code
 * getSystemCursor} allocates another (measured: one per cursor kind in use per switch).
 */
@Tag("swt")
class ResourceLeakTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final int CYCLES = 4;

  @TempDir Path temp;

  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private int cycles;

  @BeforeEach
  void open() throws Exception {
    DeviceData data = new DeviceData();
    data.tracking = true;
    display = new Display(data);
    workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failures.add(error);
              }
            });
    window = new MainWindow(display, Palette.LIGHT);
    controller =
        new ApplicationController(
            workers,
            new SwtUiDispatcher(display),
            window,
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(1L << 30, 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(0, 0, 800, 400), null);
    window.open();
    openTab("first.txt");
  }

  @AfterEach
  void close() {
    controller.quit();
    runUntil(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.shell().isDisposed();
          }
        });
    display.dispose();
    assertEquals(0, workers.liveThreads());
    assertTrue(failures.isEmpty(), failures.toString());
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  private void openTab(String name) throws IOException {
    Path file = temp.resolve(name);
    if (!Files.exists(file)) {
      StringBuilder text = new StringBuilder();
      for (int i = 0; i < 200; i++) {
        text.append("line ").append(i).append('\n');
      }
      Files.writeString(file, text.toString(), StandardCharsets.US_ASCII);
    }
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == before + 1
                    && controller.documents().loading() == 0;
              }
            }),
        name);
    drain();
  }

  private void closeTab() {
    final int before = window.tabs().getItemCount();
    controller.execute(CommandId.CLOSE);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == before - 1;
              }
            }));
    drain();
  }

  private void theme(CommandId command, final Palette expected) {
    controller.execute(command);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.palette().equals(expected);
              }
            }));
    drain();
  }

  /** Splits the tab, finds with the bar open in both views, closes the bar and unsplits. */
  private void splitAndFind() {
    controller.execute(CommandId.SPLIT_VERTICAL);
    drain();
    assertEquals(2, window.activeEditors().size());
    for (EditorPane pane : window.activeEditors()) {
      assertFalse(pane.caretImage().isDisposed());
    }
    controller.execute(CommandId.FIND);
    window.findBar().field().setText("line " + (cycles++ % 2));
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return !controller.find().state().running();
              }
            }));
    drain();
    controller.find().close();
    controller.execute(CommandId.UNSPLIT);
    drain();
  }

  /** One round of every operation; the first round creates lazily cached resources. */
  private void cycle() throws IOException {
    openTab("second.txt");
    window.showTabMenu(1).setVisible(false);
    closeTab();
    Image caret = window.activeEditor().caretImage();
    theme(CommandId.THEME_DARK, Palette.DARK);
    theme(CommandId.THEME_LIGHT, Palette.LIGHT);
    controller.execute(CommandId.ZOOM_IN);
    controller.execute(CommandId.ZOOM_OUT);
    assertTrue(caret.isDisposed());
    assertFalse(window.activeEditor().caretImage().isDisposed());
    splitAndFind();
    for (CommandGroup group : CommandGroup.values()) {
      if (group.menu()) {
        window.menuBar().menu(group).notifyListeners(SWT.Show, new Event());
      }
    }
    window.documentStateChanged();
    drain();
  }

  @Test
  void tabsThemesZoomAndMenusReleaseTheirResources() throws IOException {
    cycle();
    Map<Object, Error> baseline = tracked();
    Map<String, Integer> types = counts(baseline.keySet());
    assertTrue(types.containsKey("Font") && types.containsKey("Image"), "tracked " + types);
    for (int i = 0; i < CYCLES; i++) {
      cycle();
    }
    Map<Object, Error> after = tracked();
    assertEquals(counts(baseline.keySet()), counts(after.keySet()), leaks(baseline, after));
  }

  /** The live tracked resources with their allocation stacks, system cursors left out. */
  private Map<Object, Error> tracked() {
    DeviceData data = display.getDeviceData();
    Map<Object, Error> live = new IdentityHashMap<Object, Error>();
    for (int i = 0; i < data.objects.length; i++) {
      if (!systemCursor(data.errors[i])) {
        live.put(data.objects[i], data.errors[i]);
      }
    }
    return live;
  }

  private static boolean systemCursor(Error allocation) {
    for (StackTraceElement frame : allocation.getStackTrace()) {
      if (frame.getMethodName().equals("getSystemCursor")
          && frame.getClassName().equals(Display.class.getName())) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Integer> counts(Iterable<Object> objects) {
    Map<String, Integer> counts = new TreeMap<String, Integer>();
    for (Object object : objects) {
      counts.merge(object.getClass().getSimpleName(), 1, Integer::sum);
    }
    return counts;
  }

  /** The type and allocation stack of each resource live after the cycles and not before. */
  private static String leaks(Map<Object, Error> baseline, Map<Object, Error> after) {
    List<String> found = new ArrayList<String>();
    for (Map.Entry<Object, Error> entry : after.entrySet()) {
      if (!baseline.containsKey(entry.getKey())) {
        StringWriter stack = new StringWriter();
        entry.getValue().printStackTrace(new PrintWriter(stack));
        found.add(entry.getKey().getClass().getSimpleName() + " allocated at\n" + stack);
      }
    }
    return found.size() + " resources not in the baseline:\n" + String.join("\n", found);
  }
}
