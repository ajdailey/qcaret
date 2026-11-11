package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Tools menu in a real window: its place before Window and its cascades, the Generate dialog
 * hashing as typed, and Verify Checksums over the open document. With {@code qcaret.rm.out} set,
 * writes light and dark screenshots of both dialogs there.
 */
@Tag("swt")
class ChecksumSurfaceTest {
  private static final Duration BOUND = Duration.ofSeconds(60);
  private static final String ABC_SHA256 =
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

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
            new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("conf"))),
            Preferences.DEFAULTS,
            SwtThemeService.open(display, ThemeMode.LIGHT),
            new MemoryBudget(Runtime.getRuntime().maxMemory(), 60),
            Keymap.defaults(SwtKeys.mac()),
            false);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 1000, 500), null);
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

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void openFile(Path file) {
    final int before = window.tabs().getItemCount();
    controller.documents().open(List.of(file));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return window.tabs().getItemCount() == before + 1
                && controller.documents().loading() == 0;
          }
        },
        "opened");
  }

  /** Writes a list of good.txt (OK), bad.txt (FAILED) and gone.txt (MISSING) and opens it. */
  private void openList() throws Exception {
    Files.writeString(temp.resolve("good.txt"), "abc", StandardCharsets.US_ASCII);
    Files.writeString(temp.resolve("bad.txt"), "abd", StandardCharsets.US_ASCII);
    Path list = temp.resolve("SHA256SUMS");
    Files.writeString(
        list,
        ABC_SHA256 + "  good.txt\n" + ABC_SHA256 + "  bad.txt\n" + ABC_SHA256 + "  gone.txt\n",
        StandardCharsets.UTF_8);
    openFile(list);
  }

  private ChecksumDialog verified() {
    controller.execute(CommandId.VERIFY_CHECKSUMS);
    final ChecksumDialog dialog = window.checksumDialog();
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !dialog.status().isEmpty();
          }
        },
        "verified");
    return dialog;
  }

  private HashTextDialog hashed(CommandId command, String text, final String expected) {
    controller.execute(command);
    final HashTextDialog dialog = window.hashTextDialog();
    dialog.field().setText(text);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return dialog.hash().equals(expected);
          }
        },
        "hashed");
    return dialog;
  }

  @Test
  void theToolsMenuSitsBeforeWindowWithACascadePerAlgorithm() {
    List<String> headers = new ArrayList<String>();
    MenuItem tools = null;
    for (MenuItem header : window.shell().getMenuBar().getItems()) {
      headers.add(header.getText());
      if (header.getText().equals(CommandGroup.TOOLS.label())) {
        tools = header;
      }
    }
    assertEquals(
        headers.indexOf(CommandGroup.WINDOW.label()) - 1,
        headers.indexOf(CommandGroup.TOOLS.label()),
        headers.toString());
    List<String> cascades = new ArrayList<String>();
    for (MenuItem item : tools.getMenu().getItems()) {
      if (item.getMenu() != null) {
        cascades.add(item.getText());
        assertTrue(item.getEnabled(), item.getText());
        assertNull(item.getToolTipText(), item.getText());
        assertEquals(3, item.getMenu().getItemCount(), item.getText());
      }
    }
    assertEquals(List.of("&MD5", "SHA-&1", "SHA-&256", "SHA-&512"), cascades);
  }

  @Test
  void generateHashesAsTypedAndEscCloses() {
    HashTextDialog dialog = hashed(CommandId.SHA256_TEXT, "abc", ABC_SHA256);
    Event escape = new Event();
    escape.detail = SWT.TRAVERSE_ESCAPE;
    dialog.shell().notifyListeners(SWT.Traverse, escape);
    assertTrue(dialog.shell().isDisposed());
  }

  @Test
  void verifyShowsEachLineAndTheTotals() throws Exception {
    openList();
    ChecksumDialog dialog = verified();
    assertEquals(
        "good.txt: OK"
            + Text.DELIMITER
            + "bad.txt: FAILED"
            + Text.DELIMITER
            + "gone.txt: MISSING"
            + Text.DELIMITER,
        dialog.lines());
    assertEquals("1 OK, 1 FAILED, 1 MISSING, 0 unreadable, 0 lines not parsed.", dialog.status());
    assertFalse(dialog.progress().shown());
  }

  @Test
  @EnabledIfSystemProperty(named = "qcaret.rm.out", matches = ".+")
  void screenshots() throws Exception {
    Path out = Files.createDirectories(Path.of(System.getProperty("qcaret.rm.out")));
    openList();
    for (String theme : new String[] {"light", "dark"}) {
      if (theme.equals("dark")) {
        controller.execute(CommandId.THEME_DARK);
      }
      HashTextDialog text = hashed(CommandId.SHA256_TEXT, "abc", ABC_SHA256);
      save(text.shell(), out.resolve("checksum-text-" + theme + ".png"));
      text.shell().dispose();
      ChecksumDialog list = verified();
      save(list.shell(), out.resolve("checksum-verify-" + theme + ".png"));
      list.shell().dispose();
    }
  }

  private void save(Shell shell, Path file) {
    final long until = System.nanoTime() + 1_000_000_000L;
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        "settled");
    Point size = shell.getSize();
    Image image = new Image(display, size.x, size.y);
    GC gc = new GC(image);
    try {
      shell.print(gc);
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {image.getImageData()};
      loader.save(file.toString(), SWT.IMAGE_PNG);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }
}
