package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.WindowGeometry;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs under a display; the position assertions need X11 (Xvfb), not Wayland. */
@Tag("swt")
class MainWindowTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private Display display;

  @BeforeEach
  void createDisplay() {
    display = new Display();
  }

  @AfterEach
  void disposeDisplay() {
    display.dispose();
  }

  @Test
  void opensAtTheRequestedClientSizeAndLocation() {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    window.place(new WindowGeometry.Request(50, 40, 900, 600), null);
    window.open();
    final Shell shell = window.shell();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                Rectangle area = shell.getClientArea();
                return shell.isVisible() && area.width == 900 && area.height == 600;
              }
            },
            BOUND),
        "client area " + shell.getClientArea());
    Point location = shell.getLocation();
    assertEquals(new Point(50, 40), location);
    assertTrue(shell.getImages().length > 0);
    window.dispose();
    assertTrue(shell.isDisposed());
  }

  @Test
  void hugeRequestClampsToTheWorkArea() {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    window.place(new WindowGeometry.Request(null, null, 100000, 100000), null);
    window.open();
    Rectangle work = display.getPrimaryMonitor().getClientArea();
    Point size = window.shell().getSize();
    assertTrue(size.x <= work.width && size.y <= work.height, size + " in " + work);
    window.dispose();
  }

  @Test
  void closeRequestGoesToTheHandler() {
    MainWindow window = new MainWindow(display, Palette.LIGHT);
    final int[] requests = new int[1];
    window.onCloseRequest(
        new Runnable() {
          @Override
          public void run() {
            requests[0]++;
          }
        });
    window.open();
    window.shell().close();
    assertEquals(1, requests[0]);
    assertFalse(window.shell().isDisposed());
    window.dispose();
  }

  @Test
  void dispatcherDropsAfterDisposal() {
    SwtUiDispatcher ui = new SwtUiDispatcher(display);
    final boolean[] ran = new boolean[1];
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            ran[0] = true;
          }
        });
    assertTrue(ui.isUiThread());
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return ran[0];
              }
            },
            BOUND));
    display.dispose();
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            throw new AssertionError("ran after disposal");
          }
        });
    assertFalse(ui.isUiThread());
  }
}
