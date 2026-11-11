package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.opentest4j.TestAbortedException;

/**
 * {@link ScreenProbe} skips a test when another shell covers the test shell and passes when the
 * shell shows.
 */
@Tag("swt")
class ScreenProbeTest {
  private static final RGB COVER = new RGB(0xc8, 0x30, 0x30);

  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setBounds(40, 40, 320, 240);
    shell.open();
    settle();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  /**
   * GTK 3 only: on win32 the probe read through a covering shell (windows-2025 runner), and GTK 4
   * reads white.
   */
  @Test
  void aCoveringShellSkips() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "win32 reads the probe through a covering shell");
    assumeTrue(!"1".equals(System.getenv("SWT_GTK4")), "GC.copyArea reads white on GTK 4");
    Shell cover = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
    cover.setBackground(new Color(COVER));
    cover.setBounds(shell.getBounds());
    cover.open();
    settle();
    assertFalse(ScreenProbe.shows(shell));
    assertThrows(
        TestAbortedException.class,
        new Executable() {
          @Override
          public void execute() {
            ScreenProbe.assumeShows(shell);
          }
        });
  }

  /** GTK 3 only: the CI runners of other platforms may cover the desktop, and GTK 4 reads white. */
  @Test
  void aShownShellPasses() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "a runner desktop may cover the shell");
    assumeTrue(!"1".equals(System.getenv("SWT_GTK4")), "GC.copyArea reads white on GTK 4");
    ScreenProbe.assumeShows(shell);
  }

  @Test
  void anImageWithoutTheColorSkips() {
    Image image = new Image(display, 4, 4);
    try {
      final ImageData data = image.getImageData();
      assertThrows(
          TestAbortedException.class,
          new Executable() {
            @Override
            public void execute() {
              ScreenProbe.assumeDrawn(data, 1, 1, COVER, "a blank image");
            }
          });
    } finally {
      image.dispose();
    }
  }

  private void settle() {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !display.readAndDispatch();
          }
        },
        Duration.ofSeconds(5));
    display.update();
  }
}
