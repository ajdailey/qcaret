package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.slf4j.LoggerFactory;

/**
 * win32: under the dark native setting a drop-down combo's open list (the ComboLBox window),
 * read-only or editable, draws its selected row, which is also the hover row, in {@link
 * Win32ChromeColors#selection}'s dark colors. Windows 11 build 26300 draws that row white on white
 * under SWT's dark list theme, under a light or dark system setting, and neither {@code
 * SetWindowTheme} nor the {@code WM_CTLCOLORLISTBOX} answer changes it (measured); {@code
 * CBS_OWNERDRAWFIXED} takes effect only at creation, inside SWT's {@code Combo}.
 *
 * <p>{@link #draw} subclasses the list window through {@link Win32ListBox}. The hook paints the
 * selected row over the native drawing after each {@code WM_PAINT} and {@code WM_TIMER}, after any
 * message that moves the selection (the list draws a move without one), and into the {@code
 * WM_PRINTCLIENT} DC: the opening slide renders the list through {@code WM_PRINTCLIENT} and shows
 * it without a {@code WM_PAINT} (measured on build 26300 at 150%). When the list shows, the hook
 * posts itself {@code SHOWN} and invalidates the list when it arrives, after the slide; a combo
 * {@code SWT.Selection} on the open list (a keyboard move) invalidates it too. Under the light
 * setting the native selection stays. A member that does not resolve logs one WARN and leaves the
 * native selection. Confined to the UI thread.
 */
final class Win32ComboListSelection implements ComboListSelection {
  /** Posted to the list when it shows; the hook repaints on it and keeps it from the list. */
  private static final int SHOWN = 0x8000 + 0x0f42;

  private final Win32ListBox.Lazy members =
      new Win32ListBox.Lazy(LoggerFactory.getLogger(Win32ComboListSelection.class));

  @Override
  public void draw(Combo combo) {
    if ((combo.getStyle() & SWT.SIMPLE) != 0) {
      return;
    }
    Win32ListBox.Members found = members.get();
    if (found == null) {
      return;
    }
    try {
      new ListHook(combo, found).install();
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      members.failed(e);
    }
  }

  /** One combo's subclassed list window. */
  private static final class ListHook extends Win32ListBox.Hook {
    private final Combo combo;
    private long painted = -1;
    private boolean shown;

    ListHook(Combo combo, Win32ListBox.Members os) {
      super(combo, os);
      this.combo = combo;
    }

    void install() throws ReflectiveOperationException {
      Object info = os.newInfo.newInstance();
      os.infoSize.setInt(info, os.infoSizeof);
      os.os(os.getComboBoxInfo, os.handle.getLong(combo), info);
      long list = os.hwndList.getLong(info);
      if (list == 0) {
        return;
      }
      subclass(list);
      combo.addListener(
          SWT.Selection,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              if (shown) {
                repaint();
              }
            }
          });
    }

    /** Reads {@link DialogColors#nativeDark} per message, so a live switch applies at once. */
    @Override
    boolean dark() {
      return DialogColors.nativeDark(owner.getDisplay());
    }

    @Override
    boolean consume(long msg) {
      if (msg == SHOWN) {
        repaint();
        return true;
      }
      return false;
    }

    @Override
    void after(long hwnd, long msg, long wParam, long selected)
        throws ReflectiveOperationException {
      boolean visible = os.os(os.visible, hwnd) != 0;
      if (msg == Win32ListBox.WM_PRINTCLIENT) {
        paint(hwnd, selected, wParam);
      } else if (msg == Win32ListBox.WM_PAINT
          || msg == Win32ListBox.WM_TIMER
          || selected != painted) {
        paintWindow(hwnd, selected);
      }
      if (visible && !shown) {
        os.os(os.post, hwnd, SHOWN, 0L, 0L);
      }
      shown = visible;
      painted = selected;
    }

    @Override
    RGB[] colors(long hwnd) {
      return Win32ChromeColors.selection(true);
    }
  }
}
