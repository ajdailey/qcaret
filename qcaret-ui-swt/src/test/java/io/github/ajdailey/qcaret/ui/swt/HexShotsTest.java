package io.github.ajdailey.qcaret.ui.swt;

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
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Writes the user guide's hex view images to {@code -Dqcaret.guide.shots=<dir>} ({@code
 * tools/screenshots.sh}): a generated mock file in Split with a selection in both views ({@code
 * hex-pane.png}) and a Hex Viewer window over a generated mock binary file ({@code
 * hex-viewer.png}), light theme, each copied from the screen. With {@code -Dqcaret.rm.out=<dir>}
 * the dark theme's two are written there as well.
 */
@Tag("swt")
@EnabledIfSystemProperty(named = "qcaret.guide.shots", matches = ".+")
class HexShotsTest {
  private static final Duration BOUND = Duration.ofSeconds(60);

  private static final String TEXT =
      """
      {
        "name": "mock-shelf",
        "price": "12 €",
        "tags": ["alpha", "beta"],
        "note": "naïve café"
      }
      """;

  @TempDir Path temp;

  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private Display display;
  private MainWindow window;
  private ApplicationController controller;

  @Test
  void hexShots() throws Exception {
    Path guide = Files.createDirectories(Path.of(System.getProperty("qcaret.guide.shots")));
    String review = System.getProperty("qcaret.rm.out", "");
    String files = System.getProperty("qcaret.screenshots.files", "");
    Path dir = files.isEmpty() ? temp : Files.createDirectories(Path.of(files));
    Path text = Files.writeString(dir.resolve("shelf.json"), TEXT, StandardCharsets.UTF_8);
    Path binary = Files.write(dir.resolve("mock.bin"), binary());
    display = new Display();
    try (Workers workers =
        new Workers(
            2,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            })) {
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
      controller.loadGrammars();
      window.place(new WindowGeometry.Request(0, 0, 1180, 560), null);
      window.open();
      display.setCursorLocation(new Point(1270, 1010));
      open(text);
      controller.execute(CommandId.HEX_SPLIT);
      awaitPane(TEXT.getBytes(StandardCharsets.UTF_8).length);
      int start = TEXT.indexOf("12 €");
      controller
          .documents()
          .activeEditor()
          .selections()
          .set(SelectionSet.of(List.of(Selection.of(start, start + 4)), 0));
      controller
          .workspace()
          .openInHexViewer(new WorkspaceNode(binary, "mock.bin", WorkspaceNode.Kind.FILE));
      await(
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              List<HexWindow> windows = window.hexViews().windows();
              return windows.size() == 1 && windows.get(0).pane().rows().size() == 4096;
            }
          });
      HexWindow viewer = window.hexViews().windows().get(0);
      viewer.shell().setBounds(40, 40, viewer.shell().getSize().x, 420);
      viewer.pane().focus();
      viewer.pane().canvas().notifyListeners(SWT.FocusIn, new Event());
      for (int i = 0; i < 0x24; i++) {
        Event key = new Event();
        key.keyCode = SWT.ARROW_RIGHT;
        key.stateMask = i >= 0x20 ? SWT.SHIFT : 0;
        viewer.pane().canvas().notifyListeners(SWT.KeyDown, key);
      }
      shoot(guide.resolve("hex-pane.png"), guide.resolve("hex-viewer.png"), viewer.shell());
      if (!review.isEmpty()) {
        Path out = Files.createDirectories(Path.of(review));
        controller.execute(CommandId.THEME_DARK);
        shoot(out.resolve("hex-pane-dark.png"), out.resolve("hex-viewer-dark.png"), viewer.shell());
        controller.execute(CommandId.THEME_LIGHT);
        shoot(
            out.resolve("hex-pane-light.png"), out.resolve("hex-viewer-light.png"), viewer.shell());
      }
      controller.documents().dispose();
    } finally {
      display.dispose();
    }
    assertNull(failure.get());
  }

  /** A mock header, a run of mock text and rising bytes: 4 KiB. */
  private static byte[] binary() {
    byte[] bytes = new byte[4096];
    byte[] head = "MOCK\u0000\u0001\u0002\u0003".getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(head, 0, bytes, 0, head.length);
    byte[] text = "mock shelf record 0001 flagword".getBytes(StandardCharsets.ISO_8859_1);
    System.arraycopy(text, 0, bytes, 0x20, text.length);
    for (int i = 0x40; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 7);
    }
    return bytes;
  }

  private void open(Path file) {
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == 1 && controller.documents().loading() == 0;
          }
        });
  }

  private void awaitPane(final long size) {
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<HexPane> panes = window.hexViews().panes();
            return panes.size() == 1 && panes.get(0).rows().size() == size;
          }
        });
  }

  private void shoot(Path pane, Path viewer, Shell viewerShell) {
    settle();
    window.shell().forceActive();
    settle();
    capture(window.shell(), pane);
    viewerShell.forceActive();
    settle();
    capture(viewerShell, viewer);
  }

  /** Copies a shell's area of the screen to a PNG, without the trim past the window. */
  private void capture(Shell shell, Path file) {
    Rectangle bounds = shell.getBounds();
    Image image = new Image(display, bounds.width, bounds.height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, bounds.x, bounds.y);
    } finally {
      gc.dispose();
    }
    try {
      ImageData data = Pixels.read(image, shell);
      int width = GuideScreenshotsTest.contentWidth(data);
      int height = GuideScreenshotsTest.contentHeight(data);
      Image cut = new Image(display, width, height);
      GC cutGc = new GC(cut);
      try {
        cutGc.drawImage(image, 0, 0, width, height, 0, 0, width, height);
        ImageLoader loader = new ImageLoader();
        loader.data = new ImageData[] {cut.getImageData()};
        loader.save(file.toString(), SWT.IMAGE_PNG);
      } finally {
        cutGc.dispose();
        cut.dispose();
      }
    } finally {
      image.dispose();
    }
  }

  private void await(BooleanSupplier condition) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND));
  }

  /** Runs the event loop for a second, so the reads land and reach the screen. */
  private void settle() {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        });
  }
}
