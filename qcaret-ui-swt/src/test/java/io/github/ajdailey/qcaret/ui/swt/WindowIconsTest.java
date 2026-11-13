package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
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
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every window and dialog carries the main window's icon set. */
@Tag("swt")
class WindowIconsTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

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
            1,
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
    window.open();
  }

  @AfterEach
  void close() {
    try {
      controller.documents().dispose();
      workers.close();
      display.dispose();
    } finally {
      assertNull(failure.get());
    }
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  @Test
  void everyDialogCarriesTheMainWindowsImages() {
    Image[] icons = window.shell().getImages();
    assertTrue(icons.length > 0);
    controller.execute(CommandId.KEYBOARD_SHORTCUTS);
    controller.execute(CommandId.PREFERENCES);
    controller.execute(CommandId.ABOUT);
    window.showComparison(
        new Comparison(
            "left",
            "right",
            "a",
            "b",
            List.of(Comparison.Row.CHANGED),
            List.of(Integer.valueOf(0)),
            ""));
    window.chooseReplaceRun(
        List.of("run"),
        new Consumer<Integer>() {
          @Override
          public void accept(Integer run) {
            // Not chosen.
          }
        });
    Shell modal = Dialogs.shell(window.shell(), "modal", true);
    Dialogs.shell(modal, "nested", true);
    drain();
    Shell[] shells = display.getShells();
    assertTrue(shells.length >= 8, "shells " + shells.length);
    for (Shell shell : shells) {
      assertArrayEquals(icons, shell.getImages(), shell.getText());
    }
  }

  @Test
  void aNestedDialogTakesItsOwnersImagesAndNeverDisposesThem() {
    Image icon = new Image(display, 16, 16);
    Shell owner = new Shell(display, SWT.SHELL_TRIM);
    try {
      assertEquals(0, owner.getImages().length);
      owner.setImages(new Image[] {icon});
      Shell dialog = Dialogs.shell(owner, "dialog", true);
      Shell nested = Dialogs.shell(dialog, "nested", true);
      assertArrayEquals(new Image[] {icon}, nested.getImages());
      owner.dispose();
      assertTrue(nested.isDisposed());
      assertFalse(icon.isDisposed());
    } finally {
      icon.dispose();
    }
  }

  @Test
  void aDialogHasTheMainWindowsIconPropertyOnX11() throws Exception {
    // GTK 3 also copies a transient parent's icon; GTK 4 does not, so only GTK 4 fails without
    // Dialogs.inheritIcons.
    assumeTrue(
        "gtk".equals(SWT.getPlatform()) && System.getenv("DISPLAY") != null, "_NET_WM_ICON: X11");
    assumeFalse("wayland".equals(System.getenv("GDK_BACKEND")), "_NET_WM_ICON: X11");
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/xprop")), "xprop");
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/xwininfo")), "xwininfo");
    final String mainTitle = "QCaret icon owner " + System.nanoTime();
    final String title = "QCaret icon probe " + System.nanoTime();
    window.shell().setText(mainTitle);
    Shell dialog = Dialogs.shell(window.shell(), title, false);
    Dialogs.open(dialog, 200, 100);
    final String[] ids = new String[2];
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                ids[0] = windowId(mainTitle);
                ids[1] = windowId(title);
                return ids[0] != null && ids[1] != null;
              }
            },
            BOUND),
        "windows " + mainTitle + ", " + title);
    String owner = iconProperty(ids[0]);
    String icon = iconProperty(ids[1]);
    assertTrue(
        owner.startsWith("_NET_WM_ICON(CARDINAL) = ") && owner.length() > 1000,
        owner.substring(0, Math.min(owner.length(), 60)));
    assertTrue(icon.equals(owner), icon.length() + " characters, owner " + owner.length());
  }

  /** Returns the window's {@code _NET_WM_ICON} as decimal cardinals; xprop's default is art. */
  private static String iconProperty(String id) {
    return run(
        "/usr/bin/xprop", "-id", id, "-f", "_NET_WM_ICON", "32c", " = $0+\\n", "_NET_WM_ICON");
  }

  /** Returns the X id of the window named {@code title}, or null. */
  private static String windowId(String title) {
    String tree = run("/usr/bin/xwininfo", "-root", "-tree");
    // SWT on GTK 3 ends the X window name with a space.
    String quoted = "\"" + title;
    for (String line : tree.split("\n")) {
      String trimmed = line.trim();
      int space = trimmed.indexOf(' ');
      if (space > 0
          && trimmed.startsWith("0x")
          && (trimmed.contains(quoted + "\"") || trimmed.contains(quoted + " \""))) {
        return trimmed.substring(0, space);
      }
    }
    return null;
  }

  private static String run(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try (InputStream in = process.getInputStream()) {
        String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), command[0]);
        return out;
      }
    } catch (IOException | InterruptedException e) {
      throw new AssertionError(command[0], e);
    }
  }
}
