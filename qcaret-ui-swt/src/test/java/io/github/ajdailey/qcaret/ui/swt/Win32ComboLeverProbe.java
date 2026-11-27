package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.internal.Callback;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Applies each candidate fix for the dark read-only {@link Combo} list on win32 and reads the open
 * list from the screen: the selected row, an unselected row and the hover row. Runs only with
 * {@code -Dqcaret.a2probe=true} on win32 and never asserts a reading. Each lever appends one line
 * to the file {@code qcaret.a2probe.out} names (default {@code
 * target/diagnostics/a2probe/results.txt}) and saves the list to {@code
 * target/diagnostics/a2probe/}. {@code qcaret.a2probe.hooktheme} names the theme the {@code hook}
 * lever sets (default {@code DarkMode_Explorer}).
 */
@Tag("swt")
class Win32ComboLeverProbe {
  private static final Duration BOUND = Duration.ofSeconds(5);
  private static final String[] ITEMS = {"Alpha item", "Beta item", "Gamma item", "Delta item"};
  private static final int SELECTED = 1;
  private static final int OTHER = 0;
  private static final int HOVER = 3;
  private static final double TEXT_CONTRAST = 4.5;

  private static final int WM_PAINT = 0x000f;
  private static final int WM_GETFONT = 0x0031;
  private static final int WM_WINDOWPOSCHANGING = 0x0046;
  private static final int WM_MOUSEMOVE = 0x0200;
  private static final int WM_CTLCOLORLISTBOX = 0x0134;
  private static final int LB_GETCURSEL = 0x0188;
  private static final int LB_GETTEXT = 0x0189;
  private static final int LB_GETTEXTLEN = 0x018a;
  private static final int LB_GETITEMRECT = 0x0198;
  private static final int GWLP_WNDPROC = -4;
  private static final int SWP_SHOWWINDOW = 0x0040;
  private static final int RDW_REPAINT = 0x0001 | 0x0004 | 0x0080 | 0x0100 | 0x0400;
  private static final int TRANSPARENT = 1;
  private static final int DT_TEXT = 0x0004 | 0x0020 | 0x0800;

  /** COLORREF values, 0x00bbggrr. */
  private static final int SELECTION_FILL = 0x00885f2f;

  private static final int WHITE = 0x00ffffff;
  private static final int DARK_GROUND = 0x00202020;
  private static final int DARK_TEXT = 0x00e0e0e0;
  private static final int ORANGE = 0x000080ff;

  private Display display;
  private final List<Hook> hooks = new ArrayList<Hook>();

  /** The first exception a hook caught during the running lever, or null. */
  private String failure;

  @AfterEach
  void close() {
    removeHooks();
    if (display != null) {
      display.setDarkThemePreferred(false);
      display.dispose();
    }
  }

  @Test
  void readEachLever() throws ReflectiveOperationException {
    assumeTrue(Boolean.getBoolean("qcaret.a2probe"), "set -Dqcaret.a2probe=true to run");
    assumeTrue("win32".equals(SWT.getPlatform()), "win32 combo list");
    assumeTrue(System.getProperty("swt.autoScale") == null, "runs in the default execution");
    Path out =
        Path.of(
            System.getProperty(
                "qcaret.a2probe.out",
                Path.of("target", "diagnostics", "a2probe", "results.txt").toString()));
    display = new Display();
    SwtThemeService.open(display, ThemeMode.DARK);
    Shell parent = new Shell(display, SWT.SHELL_TRIM);
    // The client area holds ScreenProbe's 32-point child at (100, 100).
    parent.setBounds(20, 20, 260, 220);
    parent.open();
    write(
        out,
        String.format(
            "run os=%s %s swt=%d zoom=%d darkAvailable=%s",
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            SWT.getVersion(),
            parent.getMonitor().getZoom(),
            os("IsDarkModeAvailable", new Class<?>[0])));
    if (!ScreenProbe.shows(parent)) {
      write(out, "screen read does not show the test window");
      return;
    }
    for (Lever lever : levers()) {
      String line;
      failure = null;
      try {
        line = run(parent, lever);
      } catch (ReflectiveOperationException
          | IllegalStateException
          | IllegalArgumentException
          | SWTException e) {
        line = lever.name + " error " + e;
      }
      write(out, failure == null ? line : line + " | hook error " + failure);
    }
  }

  /** Opens a dialog with a read-only combo, drops its list, applies the lever and reads it. */
  private String run(Shell parent, Lever lever) throws ReflectiveOperationException {
    Shell dialog = Dialogs.shell(parent, "Combo list", true);
    try {
      Combo combo = new Combo(dialog, lever.style);
      combo.setItems(ITEMS);
      combo.select(SELECTED);
      dialog.setBounds(60, 60, 320, 220);
      dialog.open();
      dialog.forceActive();
      settle();
      long list = listWindow(combo);
      lever.prepare(combo, list);
      display.setCursorLocation(dialog.toDisplay(2, 2));
      combo.setFocus();
      ImageData closed = stable(combo);
      combo.setListVisible(true);
      final Combo shown = combo;
      final RGB closedGround = mostRows(closed);
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              display.update();
              return !mostRows(capture(shown)).equals(closedGround);
            }
          },
          BOUND);
      String applied = lever.apply(combo, list);
      os(
          "RedrawWindow",
          new Class<?>[] {long.class, rect(), long.class, int.class},
          list,
          null,
          0L,
          RDW_REPAINT);
      ImageData open = stable(combo);
      save(lever.name + "-open", open);
      int itemHeight = combo.getItemHeight();
      Row selected = row(open, SELECTED, itemHeight);
      Row other = row(open, OTHER, itemHeight);
      hover(list);
      ImageData hovered = stable(combo);
      save(lever.name + "-hover", hovered);
      Row hover = row(hovered, HOVER, itemHeight);
      Row otherHover = row(hovered, OTHER, itemHeight);
      long cursel = sendLong(list, LB_GETCURSEL, 0, 0);
      combo.setListVisible(false);
      boolean pass =
          readable(selected, other)
              && readable(hover, otherHover)
              && other.contrast >= TEXT_CONTRAST;
      return String.format(
          "%s applied=%s sel[%d] %s | other[%d] %s | hover[%d] %s cursel=%d | %s",
          lever.name,
          applied,
          SELECTED,
          selected,
          OTHER,
          other,
          HOVER,
          hover,
          cursel,
          pass ? "PASS" : "FAIL");
    } finally {
      removeHooks();
      dialog.dispose();
      settle();
    }
  }

  /** A highlighted row reads when it has a ground of its own and its text reaches 4.5:1. */
  private static boolean readable(Row highlighted, Row other) {
    return SwtThemeService.contrast(highlighted.ground, other.ground) > 1.2
        && highlighted.contrast >= TEXT_CONTRAST;
  }

  private List<Lever> levers() {
    List<Lever> levers = new ArrayList<Lever>();
    levers.add(new Lever("baseline"));
    levers.add(new ThemeLever("theme-DarkMode_Explorer", true, "DarkMode_Explorer", null));
    levers.add(new ThemeLever("theme-DarkMode_CFD", true, "DarkMode_CFD", null));
    levers.add(new ThemeLever("theme-Explorer", true, "Explorer", null));
    levers.add(new ThemeLever("theme-DarkMode_ItemsView", true, "DarkMode_ItemsView", null));
    levers.add(new ThemeLever("theme-empty", true, "", ""));
    levers.add(new ThemeLever("theme-null-lightmode", false, null, null));
    levers.add(new ThemeLever("theme-Explorer-lightmode", false, "Explorer", null));
    levers.add(new ColorLever("ctlcolor-dark", DARK_TEXT, DARK_GROUND));
    levers.add(new ColorLever("ctlcolor-orange", ORANGE, -1));
    levers.add(new DefaultColorLever());
    levers.add(new ForegroundLever());
    levers.add(new PaintLever("paint-over"));
    levers.add(new Lever("editable-baseline").editable());
    levers.add(new PaintLever("editable-paint-over").editable());
    levers.add(
        new ShowHookLever(System.getProperty("qcaret.a2probe.hooktheme", "DarkMode_Explorer")));
    return levers;
  }

  /** A lever that changes nothing: the baseline. */
  private class Lever {
    final String name;

    /** The combo's style: read-only unless {@link #editable} is called. */
    int style = SWT.READ_ONLY;

    Lever(String name) {
      this.name = name;
    }

    /** Makes the lever act on an editable combo ({@code CBS_DROPDOWN}). */
    Lever editable() {
      style = SWT.DROP_DOWN;
      return this;
    }

    /** Acts on the combo and its list before the list drops down. */
    void prepare(Combo combo, long list) throws ReflectiveOperationException {}

    /**
     * Acts on the open list, after SWT's own CBN_DROPDOWN theme; returns what the call returned.
     */
    String apply(Combo combo, long list) throws ReflectiveOperationException {
      return "-";
    }
  }

  /** {@code AllowDarkModeForWindow} and {@code SetWindowTheme} on the open list. */
  private class ThemeLever extends Lever {
    private final boolean dark;
    private final String app;
    private final String ids;

    ThemeLever(String name, boolean dark, String app, String ids) {
      super(name);
      this.dark = dark;
      this.app = app;
      this.ids = ids;
    }

    @Override
    String apply(Combo combo, long list) throws ReflectiveOperationException {
      return theme(list, dark, app, ids);
    }
  }

  /** Answers the list's {@code WM_CTLCOLORLISTBOX} on the combo window, after SWT's answer. */
  private class ColorLever extends Lever {
    private final int text;
    private final int ground;

    /** A negative ground keeps SWT's brush and background. */
    ColorLever(String name, int text, int ground) {
      super(name);
      this.text = text;
      this.ground = ground;
    }

    @Override
    void prepare(Combo combo, long list) throws ReflectiveOperationException {
      final long brush =
          ground < 0 ? 0 : (Long) os("CreateSolidBrush", new Class<?>[] {int.class}, ground);
      hook(
          new Hook(handle(combo)) {
            @Override
            long message(long hwnd, int msg, long wParam, long lParam)
                throws ReflectiveOperationException {
              long result = previous(hwnd, msg, wParam, lParam);
              if (msg != WM_CTLCOLORLISTBOX) {
                return result;
              }
              os("SetTextColor", new Class<?>[] {long.class, int.class}, wParam, text);
              if (ground < 0) {
                return result;
              }
              os("SetBkColor", new Class<?>[] {long.class, int.class}, wParam, ground);
              return brush;
            }

            @Override
            void removed() throws ReflectiveOperationException {
              if (brush != 0) {
                os("DeleteObject", new Class<?>[] {long.class}, brush);
              }
            }
          });
    }
  }

  /** Leaves the list's {@code WM_CTLCOLORLISTBOX} to {@code DefWindowProc}: system colors. */
  private class DefaultColorLever extends Lever {
    DefaultColorLever() {
      super("ctlcolor-default");
    }

    @Override
    void prepare(Combo combo, long list) throws ReflectiveOperationException {
      hook(
          new Hook(handle(combo)) {
            @Override
            long message(long hwnd, int msg, long wParam, long lParam)
                throws ReflectiveOperationException {
              if (msg == WM_CTLCOLORLISTBOX) {
                return (Long)
                    os(
                        "DefWindowProc",
                        new Class<?>[] {long.class, int.class, long.class, long.class},
                        hwnd,
                        msg,
                        wParam,
                        lParam);
              }
              return previous(hwnd, msg, wParam, lParam);
            }
          });
    }
  }

  /** The DEF3 case through public API: an orange combo foreground reaches the list's text color. */
  private class ForegroundLever extends Lever {
    ForegroundLever() {
      super("orange-foreground");
    }

    @Override
    void prepare(Combo combo, long list) {
      combo.setForeground(new Color(0xff, 0x80, 0x00));
    }
  }

  /**
   * Subclasses the list and paints over its selected row after each {@code WM_PAINT} and after any
   * message that moves the selection, which the list draws without a {@code WM_PAINT}: the
   * selection fill and white text. {@code CBS_OWNERDRAWFIXED} only takes effect at creation and
   * SWT's {@code Combo} sets its window style in package-private code, so painting over is the
   * owner-draw stand-in.
   */
  private class PaintLever extends Lever {
    PaintLever(String name) {
      super(name);
    }

    @Override
    void prepare(Combo combo, long list) throws ReflectiveOperationException {
      hook(
          new Hook(list) {
            private long painted = -1;
            private boolean busy;

            /**
             * The list messages the paint sends come back through here; {@code busy} skips them.
             */
            @Override
            long message(long hwnd, int msg, long wParam, long lParam)
                throws ReflectiveOperationException {
              long result = previous(hwnd, msg, wParam, lParam);
              if (busy) {
                return result;
              }
              busy = true;
              try {
                long cursel = sendLong(hwnd, LB_GETCURSEL, 0, 0);
                if (msg == WM_PAINT || cursel != painted) {
                  paintSelected(hwnd, cursel);
                }
                painted = cursel;
              } finally {
                busy = false;
              }
              return result;
            }
          });
    }
  }

  /**
   * Subclasses the list before it drops down and sets a theme when it is shown ({@code
   * WM_WINDOWPOSCHANGING} with {@code SWP_SHOWWINDOW}), after SWT's {@code CBN_DROPDOWN} theme: the
   * timing a product fix has without a call after the drop.
   */
  private class ShowHookLever extends Lever {
    private final String app;
    private String result = "not shown";

    ShowHookLever(String app) {
      super("hook-" + app);
      this.app = app;
    }

    @Override
    void prepare(Combo combo, long list) throws ReflectiveOperationException {
      final Object position = Class.forName(win32("WINDOWPOS")).getConstructor().newInstance();
      final int size = position.getClass().getField("sizeof").getInt(null);
      final Field flags = position.getClass().getField("flags");
      hook(
          new Hook(list) {
            @Override
            long message(long hwnd, int msg, long wParam, long lParam)
                throws ReflectiveOperationException {
              if (msg == WM_WINDOWPOSCHANGING) {
                os(
                    "MoveMemory",
                    new Class<?>[] {position.getClass(), long.class, int.class},
                    position,
                    lParam,
                    size);
                if ((flags.getInt(position) & SWP_SHOWWINDOW) != 0) {
                  result = theme(hwnd, true, app, null);
                }
              }
              return previous(hwnd, msg, wParam, lParam);
            }
          });
    }

    @Override
    String apply(Combo combo, long list) {
      return result;
    }
  }

  /** A window subclass through an SWT {@link Callback}; {@link #message} chains to the old proc. */
  private abstract class Hook {
    final long window;
    private Callback callback;
    private long old;

    Hook(long window) {
      this.window = window;
    }

    abstract long message(long hwnd, int msg, long wParam, long lParam)
        throws ReflectiveOperationException;

    void removed() throws ReflectiveOperationException {}

    long previous(long hwnd, int msg, long wParam, long lParam)
        throws ReflectiveOperationException {
      return (Long)
          os(
              "CallWindowProc",
              new Class<?>[] {long.class, long.class, int.class, long.class, long.class},
              old,
              hwnd,
              msg,
              wParam,
              lParam);
    }

    /**
     * The native entry point; SWT passes every argument as a long. A failure is recorded for the
     * lever's line and the message goes to the old proc, so no exception crosses the native frame.
     */
    @SuppressWarnings("unused")
    long windowProc(long hwnd, long msg, long wParam, long lParam) {
      try {
        return message(hwnd, (int) msg, wParam, lParam);
      } catch (ReflectiveOperationException
          | IllegalStateException
          | IllegalArgumentException
          | SWTException e) {
        if (failure == null) {
          failure = e.toString();
        }
      }
      try {
        return previous(hwnd, (int) msg, wParam, lParam);
      } catch (ReflectiveOperationException
          | IllegalStateException
          | IllegalArgumentException
          | SWTException e) {
        return 0;
      }
    }

    void install() throws ReflectiveOperationException {
      callback = new Callback(this, "windowProc", 4);
      old = getProc(window);
      setProc(window, callback.getAddress());
    }

    void remove() throws ReflectiveOperationException {
      setProc(window, old);
      callback.dispose();
      removed();
    }
  }

  private void hook(Hook hook) throws ReflectiveOperationException {
    hook.install();
    hooks.add(hook);
  }

  private void removeHooks() {
    for (int i = hooks.size() - 1; i >= 0; i--) {
      try {
        hooks.get(i).remove();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
    hooks.clear();
  }

  private static String theme(long list, boolean dark, String app, String ids)
      throws ReflectiveOperationException {
    os("AllowDarkModeForWindow", new Class<?>[] {long.class, boolean.class}, list, dark);
    Object hresult =
        os(
            "SetWindowTheme",
            new Class<?>[] {long.class, char[].class, char[].class},
            list,
            chars(app),
            chars(ids));
    return "hr=" + hresult;
  }

  private static char[] chars(String text) {
    return text == null ? null : (text + "\0").toCharArray();
  }

  /** Paints the selected row with the selection fill and white text over the list's own drawing. */
  private static void paintSelected(long list, long cursel) throws ReflectiveOperationException {
    if (cursel < 0) {
      return;
    }
    Class<?> rectClass = rect();
    Object bounds = rectClass.getConstructor().newInstance();
    os(
        "SendMessage",
        new Class<?>[] {long.class, int.class, long.class, rectClass},
        list,
        LB_GETITEMRECT,
        cursel,
        bounds);
    int length = (int) sendLong(list, LB_GETTEXTLEN, cursel, 0);
    char[] text = new char[Math.max(length, 0) + 1];
    os(
        "SendMessage",
        new Class<?>[] {long.class, int.class, long.class, char[].class},
        list,
        LB_GETTEXT,
        cursel,
        text);
    long dc = (Long) os("GetDC", new Class<?>[] {long.class}, list);
    long brush = (Long) os("CreateSolidBrush", new Class<?>[] {int.class}, SELECTION_FILL);
    long font = sendLong(list, WM_GETFONT, 0, 0);
    long oldFont = (Long) os("SelectObject", new Class<?>[] {long.class, long.class}, dc, font);
    try {
      os("FillRect", new Class<?>[] {long.class, rectClass, long.class}, dc, bounds, brush);
      os("SetBkMode", new Class<?>[] {long.class, int.class}, dc, TRANSPARENT);
      os("SetTextColor", new Class<?>[] {long.class, int.class}, dc, WHITE);
      Field left = rectClass.getField("left");
      left.setInt(bounds, left.getInt(bounds) + 3);
      os(
          "DrawText",
          new Class<?>[] {long.class, char[].class, int.class, rectClass, int.class},
          dc,
          text,
          Math.max(length, 0),
          bounds,
          DT_TEXT);
    } finally {
      os("SelectObject", new Class<?>[] {long.class, long.class}, dc, oldFont);
      os("DeleteObject", new Class<?>[] {long.class}, brush);
      os("ReleaseDC", new Class<?>[] {long.class, long.class}, list, dc);
    }
  }

  /** Sends {@code WM_MOUSEMOVE} to the middle of the hover row, in the list's client pixels. */
  private void hover(long list) throws ReflectiveOperationException {
    Class<?> rectClass = rect();
    Object bounds = rectClass.getConstructor().newInstance();
    os(
        "SendMessage",
        new Class<?>[] {long.class, int.class, long.class, rectClass},
        list,
        LB_GETITEMRECT,
        (long) HOVER,
        bounds);
    int x =
        (rectClass.getField("left").getInt(bounds) + rectClass.getField("right").getInt(bounds))
            / 2;
    int y =
        (rectClass.getField("top").getInt(bounds) + rectClass.getField("bottom").getInt(bounds))
            / 2;
    sendLong(list, WM_MOUSEMOVE, 0, ((long) y << 16) | (x & 0xffff));
  }

  private static long listWindow(Combo combo) throws ReflectiveOperationException {
    Class<?> infoClass = Class.forName(win32("COMBOBOXINFO"));
    Object info = infoClass.getConstructor().newInstance();
    infoClass.getField("cbSize").setInt(info, infoClass.getField("sizeof").getInt(null));
    os("GetComboBoxInfo", new Class<?>[] {long.class, infoClass}, handle(combo), info);
    return infoClass.getField("hwndList").getLong(info);
  }

  private static long handle(Control control) throws ReflectiveOperationException {
    return Control.class.getField("handle").getLong(control);
  }

  private static long getProc(long window) throws ReflectiveOperationException {
    return (Long)
        os("GetWindowLongPtr", new Class<?>[] {long.class, int.class}, window, GWLP_WNDPROC);
  }

  private static void setProc(long window, long proc) throws ReflectiveOperationException {
    os(
        "SetWindowLongPtr",
        new Class<?>[] {long.class, int.class, long.class},
        window,
        GWLP_WNDPROC,
        proc);
  }

  private static long sendLong(long window, int msg, long wParam, long lParam)
      throws ReflectiveOperationException {
    return (Long)
        os(
            "SendMessage",
            new Class<?>[] {long.class, int.class, long.class, long.class},
            window,
            msg,
            wParam,
            lParam);
  }

  private static Class<?> rect() throws ClassNotFoundException {
    return Class.forName(win32("RECT"));
  }

  private static String win32(String name) {
    return "org.eclipse.swt.internal.win32." + name;
  }

  /** Calls a static method of SWT's win32 {@code OS}. */
  private static Object os(String name, Class<?>[] types, Object... args)
      throws ReflectiveOperationException {
    Method method = Class.forName(win32("OS")).getMethod(name, types);
    try {
      return method.invoke(null, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }

  /** One list row: its ground, the text pixel that contrasts most with it, and that contrast. */
  private record Row(RGB ground, RGB ink, double contrast) {
    @Override
    public String toString() {
      return String.format("ground=%s ink=%s c=%.2f", hex(ground), hex(ink), contrast);
    }
  }

  /** Reads item {@code index}'s band below the list's one-pixel frame. */
  private static Row row(ImageData data, int index, int itemHeight) {
    int top = 1 + index * itemHeight;
    int bottom = Math.min(top + itemHeight - 1, data.height - 1);
    RGB ground = rowMode(data, (top + bottom) / 2);
    RGB ink = ground;
    double max = 1;
    for (int y = top + 1; y < bottom; y++) {
      for (int x = 3; x < data.width - 3; x++) {
        RGB rgb = data.palette.getRGB(data.getPixel(x, y));
        double contrast = SwtThemeService.contrast(rgb, ground);
        if (contrast > max) {
          max = contrast;
          ink = rgb;
        }
      }
    }
    return new Row(ground, ink, max);
  }

  /** Captures until two reads in a row match, so the drop animation and repaint have ended. */
  private ImageData stable(final Combo combo) {
    final ImageData[] last = {capture(combo)};
    final ImageData[] now = {null};
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            display.update();
            now[0] = capture(combo);
            boolean same = Arrays.equals(now[0].data, last[0].data);
            last[0] = now[0];
            return same;
          }
        },
        BOUND);
    return last[0];
  }

  /** Reads the screen below the combo, where its list opens, four rows and a margin high. */
  private ImageData capture(Combo combo) {
    Point size = combo.getSize();
    Point origin = combo.toDisplay(0, size.y);
    int height = combo.getItemHeight() * ITEMS.length + 4;
    Image image = new Image(display, size.x, height);
    GC gc = new GC(display);
    try {
      gc.copyArea(image, origin.x, origin.y);
      return Pixels.read(image, combo);
    } finally {
      gc.dispose();
      image.dispose();
    }
  }

  private static RGB mostRows(ImageData data) {
    Map<RGB, Integer> counts = new HashMap<RGB, Integer>();
    RGB best = null;
    int bestCount = 0;
    for (int y = 0; y < data.height; y++) {
      RGB mode = rowMode(data, y);
      Integer count = counts.get(mode);
      int next = count == null ? 1 : count + 1;
      counts.put(mode, next);
      if (next > bestCount) {
        best = mode;
        bestCount = next;
      }
    }
    return best;
  }

  private static RGB rowMode(ImageData data, int y) {
    Map<Integer, Integer> counts = new HashMap<Integer, Integer>();
    int best = 0;
    int bestCount = -1;
    for (int x = 0; x < data.width; x++) {
      int pixel = data.getPixel(x, y);
      Integer count = counts.get(pixel);
      int next = count == null ? 1 : count + 1;
      counts.put(pixel, next);
      if (next > bestCount) {
        best = pixel;
        bestCount = next;
      }
    }
    return data.palette.getRGB(best);
  }

  private void settle() {
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !display.readAndDispatch();
          }
        },
        BOUND);
    display.update();
  }

  private static void save(String name, ImageData data) {
    try {
      Path dir = Files.createDirectories(Path.of("target", "diagnostics", "a2probe"));
      ImageLoader loader = new ImageLoader();
      loader.data = new ImageData[] {data};
      loader.save(dir.resolve(name + ".png").toString(), SWT.IMAGE_PNG);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void write(Path out, String line) {
    try {
      Path dir = out.toAbsolutePath().getParent();
      if (dir != null) {
        Files.createDirectories(dir);
      }
      Files.writeString(
          out,
          line + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String hex(RGB rgb) {
    return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
  }
}
