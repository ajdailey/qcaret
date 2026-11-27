package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * {@link Win32ComboListSelection}: the SWT internals it reflects on still exist in this SWT, a
 * read-only or editable combo's list window takes another window procedure until the combo is
 * disposed, which restores the native one, and a {@code SWT.SIMPLE} combo's keeps its own. A
 * failing resolution test after an SWT upgrade names the member that went away. The painted row is
 * checked on the screen by {@code Win32ComboListTest}.
 */
@Tag("swt")
@EnabledOnOs(OS.WINDOWS)
class Win32ComboListSelectionTest {
  @Test
  void theWin32MembersResolve() throws ReflectiveOperationException {
    Win32ComboListSelection.resolve();
  }

  @Test
  void aReadOnlyComboListIsSubclassedUntilTheComboIsDisposed() throws ReflectiveOperationException {
    subclassedUntilDisposed(SWT.READ_ONLY);
  }

  @Test
  void anEditableComboListIsSubclassedUntilTheComboIsDisposed()
      throws ReflectiveOperationException {
    subclassedUntilDisposed(SWT.DROP_DOWN);
  }

  /** A listener added after {@code draw} runs after its own, while the list window exists. */
  private static void subclassedUntilDisposed(int style) throws ReflectiveOperationException {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      Combo combo = new Combo(shell, style);
      final long list = listWindow(combo);
      long nativeProc = proc(list);
      new Win32ComboListSelection().draw(combo);
      assertNotEquals(nativeProc, proc(list));
      final long[] atDispose = {0};
      combo.addListener(
          SWT.Dispose,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              try {
                atDispose[0] = proc(list);
              } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
              }
            }
          });
      combo.dispose();
      assertEquals(nativeProc, atDispose[0]);
      shell.dispose();
    } finally {
      display.dispose();
    }
  }

  @Test
  void aSimpleComboIsLeftAsItIs() throws ReflectiveOperationException {
    Display display = new Display();
    try {
      Shell shell = new Shell(display);
      Combo combo = new Combo(shell, SWT.SIMPLE);
      long list = listWindow(combo);
      long nativeProc = proc(list);
      new Win32ComboListSelection().draw(combo);
      assertEquals(nativeProc, proc(list));
      shell.dispose();
    } finally {
      display.dispose();
    }
  }

  private static long listWindow(Combo combo) throws ReflectiveOperationException {
    Class<?> info = Class.forName("org.eclipse.swt.internal.win32.COMBOBOXINFO");
    Object box = info.getConstructor().newInstance();
    info.getField("cbSize").setInt(box, info.getField("sizeof").getInt(null));
    os().getMethod("GetComboBoxInfo", long.class, info)
        .invoke(null, Control.class.getField("handle").getLong(combo), box);
    return info.getField("hwndList").getLong(box);
  }

  private static long proc(long window) throws ReflectiveOperationException {
    return (Long)
        os().getMethod("GetWindowLongPtr", long.class, int.class).invoke(null, window, -4);
  }

  private static Class<?> os() throws ClassNotFoundException {
    return Class.forName("org.eclipse.swt.internal.win32.OS");
  }
}
