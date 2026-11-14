package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.log.LogFiles;
import io.github.ajdailey.qcaret.app.log.RecentLog;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import io.github.ajdailey.qcaret.ui.swt.platform.BusyCursor;
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
import org.eclipse.swt.graphics.Cursor;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The window's busy cursor over its shell, its editors and the shells it owns, read through {@code
 * getCursor}: screenshots do not show the pointer.
 */
@Tag("swt")
class WindowBusyCursorTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  @TempDir Path temp;

  private Display display;
  private Workers workers;
  private MainWindow window;
  private ApplicationController controller;
  private Cursor busy;
  private Cursor ibeam;
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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys()),
            false);
    controller.setLogging(new RecentLog(50, 200), LogFiles.NONE);
    window.attach(controller);
    window.place(new WindowGeometry.Request(null, null, 800, 500), null);
    window.open();
    busy = BusyCursor.PLATFORM.cursor(display);
    ibeam = display.getSystemCursor(SWT.CURSOR_IBEAM);
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

  private EditorPane openFile(String name) throws Exception {
    Path file = temp.resolve(name);
    Files.writeString(file, "text\n", StandardCharsets.UTF_8);
    final int before = window.tabs().getItemCount();
    final int active = window.busy().active();
    controller.documents().open(List.of(file));
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.tabs().getItemCount() == before + 1
                    && controller.documents().loading() == 0
                    && window.busy().active() == active;
              }
            },
            BOUND),
        "opened " + name);
    drain();
    return window.activeEditor();
  }

  /** Opens a shell owned by another, with a {@code StyledText} filling it. */
  private static Shell owned(Shell owner, int style) {
    Shell dialog = new Shell(owner, SWT.DIALOG_TRIM | style);
    dialog.setLayout(new FillLayout());
    new StyledText(dialog, SWT.MULTI | SWT.READ_ONLY);
    dialog.setSize(200, 120);
    dialog.open();
    return dialog;
  }

  private static StyledText textOf(Shell dialog) {
    return (StyledText) dialog.getChildren()[0];
  }

  private void setActive() {
    window.setActive();
    drain();
  }

  private void setIdle() {
    window.setIdle();
    drain();
  }

  @Test
  void theWindowCursorReachesEditorsAndOwnedShells() throws Exception {
    EditorPane pane = openFile("a.txt");
    Shell modeless = owned(window.shell(), SWT.MODELESS);
    Shell nested = owned(modeless, SWT.APPLICATION_MODAL);
    drain();
    assertEquals(ibeam, pane.text().getCursor());
    assertEquals(ibeam, textOf(nested).getCursor());

    setActive();
    assertEquals(busy, window.shell().getCursor());
    assertEquals(busy, pane.text().getCursor());
    for (Shell dialog : List.of(modeless, nested)) {
      assertEquals(busy, dialog.getCursor());
      assertEquals(busy, textOf(dialog).getCursor());
    }

    setIdle();
    assertNull(window.shell().getCursor());
    assertEquals(ibeam, pane.text().getCursor());
    for (Shell dialog : List.of(modeless, nested)) {
      assertNull(dialog.getCursor());
      assertEquals(ibeam, textOf(dialog).getCursor());
    }
    nested.dispose();
    modeless.dispose();
  }

  @Test
  void anEditorOrShellOpenedWhileBusyStartsBusy() throws Exception {
    openFile("a.txt");
    setActive();
    EditorPane added = openFile("b.txt");
    Shell dialog = owned(window.shell(), SWT.MODELESS);
    Shell other = owned(null, SWT.NONE);
    drain();
    assertEquals(busy, added.text().getCursor());
    assertEquals(busy, dialog.getCursor());
    assertEquals(busy, textOf(dialog).getCursor());
    assertNull(other.getCursor(), "a shell the window does not own");

    setIdle();
    assertEquals(ibeam, added.text().getCursor());
    assertNull(dialog.getCursor());
    assertEquals(ibeam, textOf(dialog).getCursor());
    Shell later = owned(window.shell(), SWT.MODELESS);
    drain();
    assertNull(later.getCursor(), "idle again: the Show filter is gone");
    later.dispose();
    other.dispose();
    dialog.dispose();
  }

  @Test
  void anEditorBusyOnItsOwnStaysBusyWhenTheWindowGoesIdle() throws Exception {
    EditorPane pane = openFile("a.txt");
    pane.setBusy(true);
    setActive();
    setIdle();
    assertNull(window.shell().getCursor());
    assertEquals(busy, pane.text().getCursor());
    pane.setBusy(false);
    assertEquals(ibeam, pane.text().getCursor());

    setActive();
    pane.setBusy(true);
    pane.setBusy(false);
    assertEquals(busy, pane.text().getCursor(), "the window is still busy");
    setIdle();
    assertEquals(ibeam, pane.text().getCursor());
  }
}
