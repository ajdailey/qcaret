package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link Win32ListSelection} and {@link Win32ListBox}: every SWT internal {@link Win32ListBox}
 * reflects on still exists in this SWT, a single-selection list's window takes another window
 * procedure until the list is disposed, which restores the native one, and a {@code SWT.MULTI}
 * list's keeps its own. A failing resolution test after an SWT upgrade names the member that went
 * away. The painted row is checked on the screen by {@code Win32ListTest}.
 */
@Tag("swt")
@EnabledOnOs(OS.WINDOWS)
class Win32ListSelectionTest {
  @Test
  void theWin32MembersResolve() throws ReflectiveOperationException {
    Win32ListBox.resolve();
  }

  /** A listener added after {@code draw} runs after its own, while the list window exists. */
  @Test
  void aListIsSubclassedUntilItIsDisposed() throws ReflectiveOperationException {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      List list = new List(shell, SWT.SINGLE | SWT.BORDER);
      final long window = Control.class.getField("handle").getLong(list);
      long nativeProc = proc(window);
      new Win32ListSelection().draw(list);
      assertNotEquals(nativeProc, proc(window));
      final long[] atDispose = {0};
      list.addListener(
          SWT.Dispose,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              try {
                atDispose[0] = proc(window);
              } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
              }
            }
          });
      list.dispose();
      assertEquals(nativeProc, atDispose[0]);
      shell.dispose();
    } finally {
      display.dispose();
    }
  }

  @Test
  void aMultiSelectionListIsLeftAsItIs() throws ReflectiveOperationException {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      List list = new List(shell, SWT.MULTI);
      long window = Control.class.getField("handle").getLong(list);
      long nativeProc = proc(window);
      new Win32ListSelection().draw(list);
      assertEquals(nativeProc, proc(window));
      shell.dispose();
    } finally {
      display.dispose();
    }
  }

  private static long proc(long window) throws ReflectiveOperationException {
    return (Long)
        Class.forName("org.eclipse.swt.internal.win32.OS")
            .getMethod("GetWindowLongPtr", long.class, int.class)
            .invoke(null, window, -4);
  }
}
