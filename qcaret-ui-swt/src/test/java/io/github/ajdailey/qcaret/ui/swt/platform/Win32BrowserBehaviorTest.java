package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link Win32BrowserBehavior#menuMnemonics}: which keys from a browser open a menu, the SWT
 * internals it reflects on still existing in this SWT on win32, and a mnemonic consumed and posted.
 * A failing resolution test after an SWT upgrade names the member that went away.
 */
@Tag("swt")
class Win32BrowserBehaviorTest {
  private static Event key(int keyCode, int stateMask) {
    Event event = new Event();
    event.type = SWT.KeyDown;
    event.keyCode = keyCode;
    event.stateMask = stateMask;
    event.doit = true;
    return event;
  }

  @Test
  void altWithALetterOrDigitIsAMnemonic() {
    assertEquals('f', Win32BrowserBehavior.mnemonic(key('f', SWT.ALT)));
    assertEquals('1', Win32BrowserBehavior.mnemonic(key('1', SWT.ALT)));
    assertEquals(0, Win32BrowserBehavior.mnemonic(key('f', SWT.ALT | SWT.SHIFT)));
    assertEquals(0, Win32BrowserBehavior.mnemonic(key('f', SWT.ALT | SWT.CTRL)));
    assertEquals(0, Win32BrowserBehavior.mnemonic(key('f', SWT.CTRL)));
    assertEquals(0, Win32BrowserBehavior.mnemonic(key('f', 0)));
    assertEquals(0, Win32BrowserBehavior.mnemonic(key(SWT.F4, SWT.ALT)));
    assertEquals(0, Win32BrowserBehavior.mnemonic(key('[', SWT.ALT)));
    Event consumed = key('f', SWT.ALT);
    consumed.doit = false;
    assertEquals(0, Win32BrowserBehavior.mnemonic(consumed));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void theWin32MembersResolve() throws ReflectiveOperationException {
    Win32BrowserBehavior.resolve();
  }

  /**
   * Alt+F sent to the browser's listeners is consumed and posts one request, which does nothing
   * once the window is closed; Alt+Shift+F is not consumed.
   */
  @Test
  void aMnemonicFromTheBrowserIsConsumedAndPosted() {
    assumeTrue(BrowserBehavior.PLATFORM.unavailable() == null, "no Browser on this toolkit");
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      Browser browser = new Browser(shell, SWT.NONE);
      final List<Runnable> posted = new ArrayList<Runnable>();
      new Win32BrowserBehavior().menuMnemonics(browser, new Posts(posted));
      Event plain = key('f', SWT.ALT | SWT.SHIFT);
      browser.notifyListeners(SWT.KeyDown, plain);
      Event mnemonic = key('f', SWT.ALT);
      browser.notifyListeners(SWT.KeyDown, mnemonic);
      assertEquals(List.of(true, false, 1), List.of(plain.doit, mnemonic.doit, posted.size()));
      shell.dispose();
      posted.get(0).run();
    } finally {
      display.dispose();
    }
  }

  /** Records posted tasks without running them. */
  private static final class Posts implements UiDispatcher {
    private final List<Runnable> tasks;

    Posts(List<Runnable> tasks) {
      this.tasks = tasks;
    }

    @Override
    public void asyncExec(Runnable task) {
      tasks.add(task);
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      tasks.add(task);
    }

    @Override
    public void cancelTimer(Runnable task) {
      tasks.remove(task);
    }

    @Override
    public boolean isUiThread() {
      return true;
    }
  }
}
