package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Each {@link WindowPlacement} class on any platform: only {@link Gtk4WindowPlacement} maximizes a
 * shell again after {@code open}, and {@link Win32WindowPlacement#fit} moves and shrinks a shell
 * into a work area.
 */
@Tag("swt")
class WindowPlacementTest {
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void onlyGtk4MaximizesAgainAfterOpen() {
    Shell probe = new Shell(display);
    probe.setSize(300, 200);
    probe.open();
    probe.setMaximized(true);
    assumeTrue(maximized(probe, true), "the platform did not maximize a shell");
    probe.dispose();
    shell.setSize(300, 200);
    shell.open();
    assertTrue(maximized(shell, false));
    WindowPlacement[] none = {
      new Win32WindowPlacement(), new NativeWindowPlacement(), new GtkWindowPlacement()
    };
    for (WindowPlacement placement : none) {
      placement.afterOpen(shell, true);
      assertFalse(shell.getMaximized(), placement.getClass().getSimpleName());
    }
    Gtk4WindowPlacement gtk4 = new Gtk4WindowPlacement();
    gtk4.afterOpen(shell, false);
    assertFalse(shell.getMaximized());
    gtk4.afterOpen(shell, true);
    assertTrue(maximized(shell, true), "Gtk4WindowPlacement maximizes a restored shell");
  }

  @Test
  void win32FitMovesAndShrinksAShellIntoAWorkArea() {
    shell.setBounds(10, 10, 300, 900);
    Rectangle work = new Rectangle(40, 30, 600, 400);
    Win32WindowPlacement.fit(shell, work);
    Rectangle bounds = shell.getBounds();
    assertEquals(300, bounds.width);
    assertEquals(400, bounds.height);
    if (!"1".equals(System.getenv("SWT_GTK4"))) {
      // GTK 4 cannot position a shell.
      assertEquals(40, bounds.x);
      assertEquals(30, bounds.y);
    }
  }

  private boolean maximized(final Shell target, final boolean state) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return target.getMaximized() == state;
          }
        },
        Duration.ofSeconds(5));
  }
}
