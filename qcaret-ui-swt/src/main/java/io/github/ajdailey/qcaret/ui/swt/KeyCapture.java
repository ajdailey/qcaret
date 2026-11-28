package io.github.ajdailey.qcaret.ui.swt;

import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Makes a key field take every key stroke while it has focus. Every menu item of the display's menu
 * bars holds no accelerator from the field's focus in to its focus out or disposal, so a menu key
 * (Cmd+S on macOS) reaches the field as a key instead of running its command; an item keeps the
 * accelerator it was given meanwhile. A traversal key other than Tab and Shift+Tab reaches the
 * field as a key (Ctrl+Tab, Ctrl+Page Down, an Alt mnemonic). Call on the UI thread.
 */
final class KeyCapture {
  private final Map<MenuItem, Integer> held = new LinkedHashMap<MenuItem, Integer>();

  private KeyCapture() {}

  /**
   * Makes a field take every key stroke while it has focus.
   *
   * @param field the key field
   */
  static void install(final Text field) {
    final KeyCapture capture = new KeyCapture();
    Listener listener =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            switch (event.type) {
              case SWT.FocusIn -> capture.hold(field.getDisplay());
              case SWT.FocusOut, SWT.Dispose -> capture.release();
              case SWT.Traverse -> {
                boolean tab =
                    (event.detail == SWT.TRAVERSE_TAB_NEXT
                            || event.detail == SWT.TRAVERSE_TAB_PREVIOUS)
                        && (event.stateMask & SWT.MODIFIER_MASK & ~SWT.SHIFT) == 0;
                if (!tab) {
                  event.doit = false;
                }
              }
              default -> {}
            }
          }
        };
    field.addListener(SWT.FocusIn, listener);
    field.addListener(SWT.FocusOut, listener);
    field.addListener(SWT.Dispose, listener);
    field.addListener(SWT.Traverse, listener);
    if (field.isFocusControl()) {
      capture.hold(field.getDisplay());
    }
  }

  private void hold(Display display) {
    release();
    hold(display.getMenuBar());
    for (Shell shell : display.getShells()) {
      hold(shell.getMenuBar());
    }
  }

  private void hold(Menu menu) {
    if (menu == null || menu.isDisposed()) {
      return;
    }
    for (MenuItem item : menu.getItems()) {
      int accelerator = item.getAccelerator();
      if (accelerator != 0 && !held.containsKey(item)) {
        held.put(item, accelerator);
        item.setAccelerator(0);
      }
      hold(item.getMenu());
    }
  }

  private void release() {
    for (Map.Entry<MenuItem, Integer> entry : held.entrySet()) {
      MenuItem item = entry.getKey();
      if (!item.isDisposed() && item.getAccelerator() == 0) {
        item.setAccelerator(entry.getValue());
      }
    }
    held.clear();
  }
}
