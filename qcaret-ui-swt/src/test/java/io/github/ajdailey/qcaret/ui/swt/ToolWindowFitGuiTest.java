package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNotSame;
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
import io.github.ajdailey.qcaret.app.view.DialogSize;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dialogs and tool windows open inside a work area ({@link Dialogs#open}, {@link
 * WindowDialogs#rememberSize}), and HyperSearch reopens at the size it closed with. A fake work
 * area keeps the first case independent of the host's screen. GTK 4 cannot position a shell, so
 * there only the size is checked.
 */
@Tag("swt")
class ToolWindowFitGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  private static final boolean GTK4 =
      "gtk".equals(SWT.getPlatform()) && "1".equals(System.getenv("SWT_GTK4"));

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
            Keymap.defaults(PlatformTraits.PLATFORM.macKeys(), PlatformTraits.PLATFORM.linuxKeys()),
            false);
    window.attach(controller);
    controller.documents().newDocument();
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

  @Test
  void aDialogTallerThanTheWorkAreaOpensInsideIt() {
    final Shell dialog = Dialogs.shell(window.shell(), "Mock tall dialog", SWT.SHELL_TRIM);
    Composite body = new Composite(dialog, SWT.NONE);
    body.setLayoutData(new GridData(320, 600));
    Point preferred = dialog.computeSize(SWT.DEFAULT, SWT.DEFAULT);
    final Rectangle work = new Rectangle(40, 30, preferred.x + 200, preferred.y / 2);
    Dialogs.open(dialog, SWT.DEFAULT, SWT.DEFAULT, work);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return inside(dialog.getBounds(), work) && dialog.getSize().y == work.height;
              }
            }),
        dialog.getBounds() + " not filling the height of " + work);
    dialog.dispose();
  }

  @Test
  void hyperSearchReopensAtTheSizeItClosedWith() {
    Shell first = hyperSearch();
    Point opened = first.getSize();
    first.setSize(opened.x + 60, opened.y + 40);
    final Point closed = first.getSize();
    first.dispose();
    final Shell second = hyperSearch();
    assertNotSame(first, second);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return second.getSize().equals(closed);
              }
            }),
        "reopened at " + second.getSize() + ", closed at " + closed);
  }

  @Test
  void aRememberedSizeLargerThanTheMonitorIsFittedToItsWorkArea() {
    controller.rememberDialogSize("hyperSearch", new DialogSize(20000, 20000));
    final Shell shell = hyperSearch();
    final Rectangle work = window.shell().getMonitor().getClientArea();
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return inside(shell.getBounds(), work);
              }
            }),
        shell.getBounds() + " outside " + work);
  }

  private Shell hyperSearch() {
    controller.execute(CommandId.HYPERSEARCH);
    assertTrue(
        runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.searchDialog() != null && !window.searchDialog().shell().isDisposed();
              }
            }));
    return window.searchDialog().shell();
  }

  /** Reports whether bounds fit an area; on GTK 4 only the size. */
  private static boolean inside(Rectangle bounds, Rectangle area) {
    boolean fits = bounds.width <= area.width && bounds.height <= area.height;
    if (GTK4) {
      return fits;
    }
    return fits
        && bounds.x >= area.x
        && bounds.y >= area.y
        && bounds.x + bounds.width <= area.x + area.width
        && bounds.y + bounds.height <= area.y + area.height;
  }

  private boolean runUntil(BooleanSupplier condition) {
    return EventLoop.runUntil(display, condition, BOUND);
  }
}
