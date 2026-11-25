package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import io.github.ajdailey.qcaret.ui.swt.SwtUiDispatcher;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link CocoaChrome} on any platform, for a control created while {@link SWT#Skin} is held back,
 * as cocoa holds it while a menu tracks. A Display filter drops every Skin event, so only the
 * chrome's Show path and its sweep while a menu shows can color the control.
 */
@Tag("swt")
class CocoaChromeTest {
  private static final RGB DARK_LIST = new RGB(0x1e, 0x1e, 0x1e);
  private static final Duration BOUND = Duration.ofSeconds(10);

  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    display.addFilter(
        SWT.Skin,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            event.type = SWT.None;
          }
        });
    shell = new Shell(display);
    shell.open();
    Text probe = new Text(shell, SWT.SINGLE);
    assumeFalse(DARK_LIST.equals(probe.getBackground().getRGB()), "the host's fields are dark");
    probe.dispose();
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private void drain() {
    while (display.readAndDispatch()) {
      // Runs queued work.
    }
  }

  /** Runs the event loop past three sweep intervals. */
  private void runPastSweeps() {
    final boolean[] fired = new boolean[1];
    display.timerExec(
        3 * CocoaChrome.SWEEP_MILLIS,
        new Runnable() {
          @Override
          public void run() {
            fired[0] = true;
          }
        });
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return fired[0];
              }
            },
            BOUND));
  }

  @Test
  void aControlCreatedWhileAPopupMenuShowsTakesThePinnedSideAtTheNextSweep() {
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    chrome.apply(true);
    Menu menu = new Menu(shell, SWT.POP_UP);
    final Text[] created = new Text[1];
    menu.addListener(
        SWT.Show,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            created[0] = new Text(shell, SWT.SINGLE);
          }
        });
    menu.notifyListeners(SWT.Show, new Event());
    assertNotEquals(DARK_LIST, created[0].getBackground().getRGB());
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return DARK_LIST.equals(created[0].getBackground().getRGB());
              }
            },
            BOUND));
    menu.notifyListeners(SWT.Hide, new Event());
    Text after = new Text(shell, SWT.SINGLE);
    runPastSweeps();
    assertNotEquals(DARK_LIST, after.getBackground().getRGB());
    Menu bar = new Menu(shell, SWT.BAR);
    bar.notifyListeners(SWT.Show, new Event());
    Text underBar = new Text(shell, SWT.SINGLE);
    runPastSweeps();
    assertNotEquals(DARK_LIST, underBar.getBackground().getRGB());
  }

  @Test
  void aStaleControlTakesThePinnedSideWhenShown() {
    CocoaChrome chrome = new CocoaChrome(display, false, new SwtUiDispatcher(display));
    chrome.apply(true);
    Text field = new Text(shell, SWT.SINGLE);
    drain();
    assertNotEquals(DARK_LIST, field.getBackground().getRGB());
    field.notifyListeners(SWT.Show, new Event());
    assertEquals(DARK_LIST, field.getBackground().getRGB());
    chrome.apply(false);
    assertNotEquals(DARK_LIST, field.getBackground().getRGB());
  }
}
