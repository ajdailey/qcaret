package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.List;
import org.slf4j.LoggerFactory;

/**
 * win32: under the dark native setting a single-selection {@link List} (a ListBox window) draws its
 * selected row {@link #FOCUSED} with focus and {@link Win32UnfocusedSelection#BACKGROUND} without,
 * text {@link Win32UnfocusedSelection#TEXT}, as a dark dialog tree draws its rows. Windows 11 draws
 * that row solid {@code #ffffff} under SWT's dark theme, below the dialog's white text (OBERON, at
 * 150% and 100%).
 *
 * <p>{@link #draw} subclasses the list window through {@link Win32ListBox}. The hook paints the
 * selected row over the native drawing after each {@code WM_PAINT}, into the {@code WM_PRINTCLIENT}
 * DC, and after any message that moves the selection or the focus. The side is taken once, at
 * {@link #draw}, from {@link Win32DialogColors#colored}: a list in a dialog colored dark paints
 * until it is disposed, any other keeps the native selection, so a live switch leaves the list on
 * the side its dialog was colored on. A member that does not resolve logs one WARN and leaves the
 * native selection. Confined to the UI thread.
 */
final class Win32ListSelection implements ListSelection {
  /** The focused selected row's background: a dark dialog tree's native focused selection. */
  static final RGB FOCUSED = new RGB(0x62, 0x62, 0x62);

  private final Win32ListBox.Lazy members =
      new Win32ListBox.Lazy(LoggerFactory.getLogger(Win32ListSelection.class));

  @Override
  public void draw(List list) {
    if ((list.getStyle() & SWT.MULTI) != 0) {
      return;
    }
    Win32ListBox.Members found = members.get();
    if (found == null) {
      return;
    }
    try {
      new Hook(list, found).subclass(found.handle.getLong(list));
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      members.failed(e);
    }
  }

  /** One list's subclassed window. */
  private static final class Hook extends Win32ListBox.Hook {
    /** The side the list's dialog was colored on at creation. */
    private final boolean dark;

    private long painted = -1;
    private boolean paintedFocused;

    Hook(List list, Win32ListBox.Members os) {
      super(list, os);
      dark = Win32DialogColors.INSTANCE.colored(list);
    }

    @Override
    boolean dark() {
      return dark;
    }

    @Override
    boolean consume(long msg) {
      return false;
    }

    @Override
    void after(long hwnd, long msg, long wParam, long selected)
        throws ReflectiveOperationException {
      boolean focused = focused(hwnd);
      if (msg == Win32ListBox.WM_PRINTCLIENT) {
        paint(hwnd, selected, wParam);
      } else if (msg == Win32ListBox.WM_PAINT || selected != painted || focused != paintedFocused) {
        paintWindow(hwnd, selected);
      }
      painted = selected;
      paintedFocused = focused;
    }

    @Override
    RGB[] colors(long hwnd) throws ReflectiveOperationException {
      return new RGB[] {
        focused(hwnd) ? FOCUSED : Win32UnfocusedSelection.BACKGROUND, Win32UnfocusedSelection.TEXT
      };
    }

    private boolean focused(long hwnd) throws ReflectiveOperationException {
      return os.os(os.focus) == hwnd;
    }
  }
}
