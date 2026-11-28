package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * win32: the native ListBox window subclass and selected-row painting that {@link
 * Win32ComboListSelection} and {@link Win32ListSelection} share. {@link #resolve} finds SWT's
 * {@code Control.handle}, {@code internal.Callback} and the {@code internal.win32.OS} calls and
 * structs in the running SWT; a {@link Hook} subclasses one ListBox window through them and paints
 * its selected row over the native drawing while {@link Hook#dark} holds. Confined to the UI
 * thread.
 */
final class Win32ListBox {
  static final int WM_PAINT = 0x000f;
  static final int WM_TIMER = 0x0113;
  static final int WM_PRINTCLIENT = 0x0318;

  private static final int WM_GETFONT = 0x0031;
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

  private static final Logger LOG = LoggerFactory.getLogger(Win32ListBox.class);

  private Win32ListBox() {}

  /**
   * Resolves {@link Members} at the first call and logs one WARN when that or a later call fails.
   */
  static final class Lazy {
    private final Logger log;

    /** The resolved members, the failure that stopped them, or null before the first call. */
    private Object members;

    /** Creates a resolver that logs its WARN to {@code log}, the owning class's logger. */
    Lazy(Logger log) {
      this.log = log;
    }

    /** Returns the members, or null once a resolution or install has failed. */
    Members get() {
      if (members == null) {
        try {
          members = resolve();
        } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
          failed(e);
        }
      }
      return members instanceof Members found ? found : null;
    }

    /** Stops every later call and logs the cause. */
    void failed(Throwable cause) {
      members = cause;
      log.warn(
          "Dark lists keep the native selection: a reflective call into SWT internals failed",
          cause);
    }
  }

  /**
   * Resolves every member in the running SWT.
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
    found.focus = os.getMethod("GetFocus");
    found.invalidate = os.getMethod("InvalidateRect", long.class, found.rect, boolean.class);
    found.post = os.getMethod("PostMessage", long.class, int.class, long.class, long.class);
    return found;
  }

  /** The SWT internals a {@link Hook} subclasses and paints through. */
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
    Method focus;
    Method invalidate;
    Method post;

    long os(Method method, Object... args) throws ReflectiveOperationException {
      return number(method.invoke(null, args));
    }

    private static long number(Object value) {
      return value instanceof Boolean flag ? (flag ? 1 : 0) : ((Number) value).longValue();
    }
  }

  /**
   * One subclassed ListBox window, owned by a control whose disposal restores the native window
   * procedure and frees the callback. After each native message, while the dark native setting is
   * on, {@link #after} decides whether to paint; {@link #paint} fills the selected row and draws
   * its text in {@link #colors}.
   */
  abstract static class Hook {
    final Control owner;
    final Members os;
    long window;
    private Object callback;
    private long old;
    private boolean busy;
    private boolean broken;

    Hook(Control owner, Members os) {
      this.owner = owner;
      this.os = os;
    }

    /**
     * Subclasses the window until the owner is disposed.
     *
     * @param hwnd the ListBox window
     */
    final void subclass(long hwnd) throws ReflectiveOperationException {
      window = hwnd;
      callback = os.newCallback.newInstance(this, "windowProc", 4);
      old = os.os(os.getProc, hwnd, GWLP_WNDPROC);
      long address = ((Number) os.address.invoke(callback)).longValue();
      os.os(os.setProc, hwnd, GWLP_WNDPROC, address);
      owner.addListener(
          SWT.Dispose,
          new Listener() {
            @Override
            public void handleEvent(Event event) {
              remove();
            }
          });
    }

    /** Restores the window procedure and frees the callback, before SWT destroys the window. */
    private void remove() {
      try {
        os.os(os.setProc, window, GWLP_WNDPROC, old);
        os.dispose.invoke(callback);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        LOG.warn("Restoring a list window's procedure failed", e);
      }
    }

    /** Invalidates the window, so a {@code WM_PAINT} repaints it and the selected row. */
    final void repaint() {
      if (owner.isDisposed() || broken) {
        return;
      }
      try {
        os.os(os.invalidate, window, null, false);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        stop(e);
      }
    }

    /**
     * The window procedure: {@link #consume}, else the native one followed by {@link #after} while
     * {@link #dark} holds. SWT's callback passes every argument as a long.
     */
    final long windowProc(long hwnd, long msg, long wParam, long lParam) {
      if (consume(msg)) {
        return 0;
      }
      long result;
      try {
        result = os.os(os.callProc, old, hwnd, (int) msg, wParam, lParam);
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        LOG.warn("A list window's procedure failed", e);
        return 0;
      }
      if (busy || broken || !dark()) {
        return result;
      }
      busy = true;
      try {
        after(hwnd, msg, wParam, os.os(os.send, hwnd, LB_GETCURSEL, 0L, 0L));
      } catch (ReflectiveOperationException | IllegalArgumentException e) {
        stop(e);
      } finally {
        busy = false;
      }
      return result;
    }

    private void stop(Exception cause) {
      broken = true;
      LOG.warn("Dark list selection painting stopped: a reflective call failed", cause);
    }

    /**
     * Reports whether the window draws the dark selection, checked after each native message.
     *
     * @return true to run {@link #after}
     */
    abstract boolean dark();

    /**
     * Handles a message the hook keeps from the native procedure.
     *
     * @return true when the message is consumed
     */
    abstract boolean consume(long msg);

    /**
     * Runs after the native procedure handled a message while {@link #dark} holds.
     *
     * @param selected the selected row, or -1
     */
    abstract void after(long hwnd, long msg, long wParam, long selected)
        throws ReflectiveOperationException;

    /**
     * Returns the selected row's background and text.
     *
     * @param hwnd the window
     * @return {background, text}
     */
    abstract RGB[] colors(long hwnd) throws ReflectiveOperationException;

    /** Paints the selected row through the window DC. */
    final void paintWindow(long hwnd, long selected) throws ReflectiveOperationException {
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

    /** Fills the selected row on {@code dc} and draws its text in {@link #colors}. */
    final void paint(long hwnd, long selected, long dc) throws ReflectiveOperationException {
      if (selected < 0) {
        return;
      }
      Object bounds = os.newRect.newInstance();
      os.os(os.sendRect, hwnd, LB_GETITEMRECT, selected, bounds);
      int length = (int) Math.max(os.os(os.send, hwnd, LB_GETTEXTLEN, selected, 0L), 0);
      char[] text = new char[length + 1];
      os.os(os.sendChars, hwnd, LB_GETTEXT, selected, text);
      RGB[] colors = colors(hwnd);
      long brush = os.os(os.solidBrush, colorRef(colors[0]));
      long font = os.os(os.send, hwnd, WM_GETFONT, 0L, 0L);
      long oldFont = os.os(os.selectObject, dc, font);
      try {
        os.os(os.fillRect, dc, bounds, brush);
        os.os(os.bkMode, dc, TRANSPARENT);
        os.os(os.textColor, dc, colorRef(colors[1]));
        int inset = INSET * owner.getMonitor().getZoom() / 100;
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
