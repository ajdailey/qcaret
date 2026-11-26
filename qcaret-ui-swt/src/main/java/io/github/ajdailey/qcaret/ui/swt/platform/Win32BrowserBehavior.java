package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The embedded browser on win32: SWT's Edge (WebView2). Menu mnemonics reach the shell through
 * SWT's {@code Control.handle} and {@code internal.win32.OS.SendMessage}, resolved at the first
 * mnemonic; a member that does not resolve logs one WARN, and Alt with a letter then opens no menu
 * from the browser.
 */
final class Win32BrowserBehavior implements BrowserBehavior {
  /** Win32 {@code WM_SYSCOMMAND}. */
  static final int WM_SYSCOMMAND = 0x0112;

  /** Win32 {@code SC_KEYMENU}. */
  static final long SC_KEYMENU = 0xF100;

  private static final Logger LOG = LoggerFactory.getLogger(Win32BrowserBehavior.class);

  /** The resolved members, the failure that stopped them, or null before the first mnemonic. */
  private Object members;

  @Override
  public String unavailable() {
    return null;
  }

  @Override
  public boolean followUngestured(String location, Consumer<String> follow) {
    follow.accept(location);
    return true;
  }

  @Override
  public void scrollToAnchor(Runnable scroll) {}

  @Override
  public void pageChanged(Runnable check) {}

  @Override
  public void menuMnemonics(final Browser browser, final UiDispatcher ui) {
    browser.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            final char key = mnemonic(event);
            if (key == 0) {
              return;
            }
            event.doit = false;
            final Shell shell = browser.getShell();
            ui.asyncExec(
                new Runnable() {
                  @Override
                  public void run() {
                    if (!shell.isDisposed()) {
                      openMenu(shell, key);
                    }
                  }
                });
          }
        });
  }

  /**
   * Returns the mnemonic a key event names: a letter or digit with Alt and no other modifier, in a
   * key no listener consumed.
   *
   * @param event a {@code KeyDown} event
   * @return the lower-case char, or 0
   */
  static char mnemonic(Event event) {
    int key = event.keyCode;
    boolean letterOrDigit = key >= 'a' && key <= 'z' || key >= '0' && key <= '9';
    return event.doit && letterOrDigit && (event.stateMask & SWT.MODIFIER_MASK) == SWT.ALT
        ? (char) key
        : 0;
  }

  /** Sends the shell {@code SC_KEYMENU} with the key, as Windows does for Alt and the key. */
  private void openMenu(Shell shell, char key) {
    Members found = members();
    if (found == null) {
      return;
    }
    try {
      found
          .sendMessage()
          .invoke(null, found.handle().getLong(shell), WM_SYSCOMMAND, SC_KEYMENU, (long) key);
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      failed(e);
    }
  }

  private Members members() {
    if (members == null) {
      try {
        members = resolve();
      } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
        failed(e);
      }
    }
    return members instanceof Members found ? found : null;
  }

  private void failed(Throwable cause) {
    members = cause;
    LOG.warn(
        "Alt with a letter opens no menu from the Markdown preview: a reflective call into SWT"
            + " internals failed",
        cause);
  }

  /**
   * Resolves SWT's {@code Control.handle} and {@code internal.win32.OS.SendMessage(long, int, long,
   * long)} in the running SWT.
   *
   * @return the members
   * @throws ReflectiveOperationException when one is missing
   */
  static Members resolve() throws ReflectiveOperationException {
    return new Members(
        Control.class.getField("handle"),
        Class.forName("org.eclipse.swt.internal.win32.OS", false, Shell.class.getClassLoader())
            .getMethod("SendMessage", long.class, int.class, long.class, long.class));
  }

  /**
   * The members a mnemonic is sent through.
   *
   * @param handle {@code Control.handle}
   * @param sendMessage {@code OS.SendMessage}
   */
  record Members(Field handle, Method sendMessage) {}
}
