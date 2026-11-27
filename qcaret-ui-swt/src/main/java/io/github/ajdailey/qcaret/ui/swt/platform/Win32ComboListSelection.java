package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * win32: under the dark native setting a drop-down combo's open list (the ComboLBox window),
 * read-only or editable, draws its selected row, which is also the hover row, in {@link
 * Win32ChromeColors#selection}'s dark colors. Windows 11 build 26300 draws that row white on white
 * under SWT's dark list theme, under a light or dark system setting, and neither {@code
 * SetWindowTheme} nor the {@code WM_CTLCOLORLISTBOX} answer changes it (measured); {@code
 * CBS_OWNERDRAWFIXED} takes effect only at creation, inside SWT's {@code Combo}.
 *
 * <p>{@link #draw} subclasses the list window through SWT's {@code internal.Callback} and {@code
 * internal.win32.OS}, resolved at the first combo. It paints the selected row over the native
 * drawing after each {@code WM_PAINT} and {@code WM_TIMER}, after any message that moves the
 * selection (the list draws a move without one), and into the {@code WM_PRINTCLIENT} DC: the
 * opening slide renders the list through {@code WM_PRINTCLIENT} and shows it without a {@code
 * WM_PAINT} (measured on build 26300 at 150%). When the list shows, the hook posts itself {@code
 * SHOWN} and invalidates the list when it arrives, after the slide; a combo {@code SWT.Selection}
 * on the open list (a keyboard move) invalidates it too. The setting is read per message, so a live
 * switch applies at the next paint; under the light setting the native selection stays. Disposing
 * the combo restores the window procedure and frees the callback. A member that does not resolve
 * logs one WARN and leaves the native selection. Confined to the UI thread.
 */
final class Win32ComboListSelection implements ComboListSelection {
  private static final int WM_PAINT = 0x000f;
  private static final int WM_GETFONT = 0x0031;
  private static final int WM_TIMER = 0x0113;
  private static final int WM_PRINTCLIENT = 0x0318;

  /** Posted to the list when it shows; the hook repaints on it and keeps it from the list. */
  private static final int SHOWN = 0x8000 + 0x0f42;

  private static final int LB_GETCURSEL = 0x0188;
  private static final int LB_GETTEXT = 0x0189;
  private static final int LB_GETTEXTLEN = 0x018a;
  private static final int LB_GETITEMRECT = 0x0198;
  private static final int GWLP_WNDPROC = -4;
  private static final int TRANSPARENT = 1;

  /** {@code DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX}. */
  private static final int DT_TEXT = 0x0004 | 0x0020 | 0x0800;

  /** Text inset from the row's left edge at 100 %, in pixels. */
  private static final int INSET = 2;

  private static final Logger LOG = LoggerFactory.getLogger(Win32ComboListSelection.class);

  /** The resolved members, the failure that stopped them, or null before the first combo. */
  private Object members;

  @Override
  public void draw(Combo combo) {
    if ((combo.getStyle() & SWT.SIMPLE) != 0) {
      return;
    }
    Members found = members();
    if (found == null) {
      return;
    }
    try {
      new ListHook(combo, found).install();
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
        "Dark combo lists keep the native selection: a reflective call into SWT"
            + " internals failed",
        cause);
  }

  /**
   * Resolves SWT's {@code Control.handle}, {@code internal.Callback} and the {@code
   * internal.win32.OS} calls and structs the list is subclassed and painted through, in the running
   * SWT.
   *
   * @return the members
   * @throws ReflectiveOperationException when one is missing
   */
  static Members resolve() throws ReflectiveOperationException {
    ClassLoader loader = Shell.class.getClassLoader();
    String win32 = "org.eclipse.swt.internal.win32.";
    Class<?> os = Class.forName(win32 + "OS", false, loader);
    Members found = new Members();
    found.handle = Control.class.getField("handle");
    found.info = Class.forName(win32 + "COMBOBOXINFO", false, loader);
    found.newInfo = found.info.getConstructor();
    found.infoSize = found.info.getField("cbSize");
    found.infoSizeof = found.info.getField("sizeof").getInt(null);
    found.hwndList = found.info.getField("hwndList");
    found.rect = Class.forName(win32 + "RECT", false, loader);
    found.newRect = found.rect.getConstructor();
    found.rectLeft = found.rect.getField("left");
    Class<?> callback = Class.forName("org.eclipse.swt.internal.Callback", false, loader);
    found.newCallback = callback.getConstructor(Object.class, String.class, int.class);
    found.address = callback.getMethod("getAddress");
    found.dispose = callback.getMethod("dispose");
    found.getComboBoxInfo = os.getMethod("GetComboBoxInfo", long.class, found.info);
    found.getProc = os.getMethod("GetWindowLongPtr", long.class, int.class);
    found.setProc = os.getMethod("SetWindowLongPtr", long.class, int.class, long.class);
    found.callProc =
        os.getMethod("CallWindowProc", long.class, long.class, int.class, long.class, long.class);
    found.send = os.getMethod("SendMessage", long.class, int.class, long.class, long.class);
    found.sendRect = os.getMethod("SendMessage", long.class, int.class, long.class, found.rect);
    found.sendChars = os.getMethod("SendMessage", long.class, int.class, long.class, char[].class);
    found.getDc = os.getMethod("GetDC", long.class);
    found.releaseDc = os.getMethod("ReleaseDC", long.class, long.class);
    found.solidBrush = os.getMethod("CreateSolidBrush", int.class);
    found.deleteObject = os.getMethod("DeleteObject", long.class);
    found.selectObject = os.getMethod("SelectObject", long.class, long.class);
    found.fillRect = os.getMethod("FillRect", long.class, found.rect, long.class);
    found.bkMode = os.getMethod("SetBkMode", long.class, int.class);
    found.textColor = os.getMethod("SetTextColor", long.class, int.class);
    found.drawText =
        os.getMethod("DrawText", long.class, char[].class, int.class, found.rect, int.class);
    found.visible = os.getMethod("IsWindowVisible", long.class);
    found.invalidate = os.getMethod("InvalidateRect", long.class, found.rect, boolean.class);
    found.post = os.getMethod("PostMessage", long.class, int.class, long.class, long.class);
    return found;
  }

  /** The members {@link #resolve} finds. */
  static final class Members {
    Field handle;
    Class<?> info;
    Constructor<?> newInfo;
    Field infoSize;
    int infoSizeof;
    Field hwndList;
    Class<?> rect;
    Constructor<?> newRect;
    Field rectLeft;
    Constructor<?> newCallback;
    Method address;
    Method dispose;
    Method getComboBoxInfo;
    Method getProc;
    Method setProc;
    Method callProc;
    Method send;
    Method sendRect;
    Method sendChars;
    Method getDc;
    Method releaseDc;
    Method solidBrush;
    Method deleteObject;
    Method selectObject;
    Method fillRect;
    Method bkMode;
    Method textColor;
    Method drawText;
    Method visible;
    Method invalidate;
    Method post;

    long os(Method method, Object... args) throws ReflectiveOperationException {
      return number(method.invoke(null, args));
    }

    private static long number(Object value) {
      return value instanceof Boolean flag ? (flag ? 1 : 0) : ((Number) value).longValue();
    }
  }

  /** One combo's subclassed list window. */
  static final class ListHook {
    private final Combo combo;
    private final Members os;
    private Object callback;
    private long list;
    private long old;
    private long painted = -1;
    private boolean shown;
    private boolean busy;
    private boolean broken;

    ListHook(Combo combo, Members os) {
      this.combo = combo;
      this.os = os;
    }

    void install() throws ReflectiveOperationException {
      Object info = os.newInfo.newInstance();
      os.infoSize.setInt(info, os.infoSizeof);
      os.os(os.getComboBoxInfo, os.handle.getLong(combo), info);
      list = os.hwndList.getLong(info);
      if (list == 0) {
        return;
      }
      callback = os.newCallback.newInstance(this, "windowProc", 4);
      old = os.os(os.getProc, list, GWLP_WNDPROC);
      long address = ((Number) os.address.invoke(callback)).longValue();
      os.os(os.setProc, list, GWLP_WNDPROC, address);
      combo.addListener(
          SWT.Dispose,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              remove();
            }
          });
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

    /** Invalidates the open list, so a {@code WM_PAINT} repaints it and the selected row. */
    private void repaint() {
      if (combo.isDisposed() || broken) {
        return;
      }
      try {
        os.os(os.invalidate, list, null, false);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        broken = true;
        LOG.warn("Dark combo list selection painting stopped: a reflective call failed", e);
      }
    }

    /** Restores the list's window procedure and frees the callback, before SWT destroys it. */
    void remove() {
      try {
        os.os(os.setProc, list, GWLP_WNDPROC, old);
        os.dispose.invoke(callback);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        LOG.warn("Restoring a combo list's window procedure failed", e);
      }
    }

    /**
     * The list's window procedure: the native one, then the selected row painted over it while the
     * dark native setting is on. SWT's callback passes every argument as a long.
     */
    long windowProc(long hwnd, long msg, long wParam, long lParam) {
      if (msg == SHOWN) {
        repaint();
        return 0;
      }
      long result;
      try {
        result = os.os(os.callProc, old, hwnd, (int) msg, wParam, lParam);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        LOG.warn("A combo list's window procedure failed", e);
        return 0;
      }
      if (busy || broken || !DialogColors.nativeDark(combo.getDisplay())) {
        return result;
      }
      busy = true;
      try {
        long selected = os.os(os.send, hwnd, LB_GETCURSEL, 0L, 0L);
        boolean visible = os.os(os.visible, hwnd) != 0;
        if (msg == WM_PRINTCLIENT) {
          paint(hwnd, selected, wParam);
        } else if (msg == WM_PAINT || msg == WM_TIMER || selected != painted) {
          paintWindow(hwnd, selected);
        }
        if (visible && !shown) {
          os.os(os.post, hwnd, SHOWN, 0L, 0L);
        }
        shown = visible;
        painted = selected;
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        broken = true;
        LOG.warn("Dark combo list selection painting stopped: a reflective call failed", e);
      } finally {
        busy = false;
      }
      return result;
    }

    /** Paints the selected row through the list's window DC. */
    private void paintWindow(long hwnd, long selected) throws ReflectiveOperationException {
      if (selected < 0) {
        return;
      }
      long dc = os.os(os.getDc, hwnd);
      try {
        paint(hwnd, selected, dc);
      } finally {
        os.os(os.releaseDc, hwnd, dc);
      }
    }

    /** Fills the selected row on {@code dc} and draws its text in the dark selection colors. */
    private void paint(long hwnd, long selected, long dc) throws ReflectiveOperationException {
      if (selected < 0) {
        return;
      }
      Object bounds = os.newRect.newInstance();
      os.os(os.sendRect, hwnd, LB_GETITEMRECT, selected, bounds);
      int length = (int) Math.max(os.os(os.send, hwnd, LB_GETTEXTLEN, selected, 0L), 0);
      char[] text = new char[length + 1];
      os.os(os.sendChars, hwnd, LB_GETTEXT, selected, text);
      RGB[] colors = Win32ChromeColors.selection(true);
      long brush = os.os(os.solidBrush, colorRef(colors[0]));
      long font = os.os(os.send, hwnd, WM_GETFONT, 0L, 0L);
      long oldFont = os.os(os.selectObject, dc, font);
      try {
        os.os(os.fillRect, dc, bounds, brush);
        os.os(os.bkMode, dc, TRANSPARENT);
        os.os(os.textColor, dc, colorRef(colors[1]));
        int inset = INSET * combo.getMonitor().getZoom() / 100;
        os.rectLeft.setInt(bounds, os.rectLeft.getInt(bounds) + inset);
        os.os(os.drawText, dc, text, length, bounds, DT_TEXT);
      } finally {
        os.os(os.selectObject, dc, oldFont);
        os.os(os.deleteObject, brush);
      }
    }

    private static int colorRef(RGB rgb) {
      return rgb.red | rgb.green << 8 | rgb.blue << 16;
    }
  }
}
