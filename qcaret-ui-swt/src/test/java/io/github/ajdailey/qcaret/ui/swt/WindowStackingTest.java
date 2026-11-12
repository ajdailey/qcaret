package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Each platform's {@link WindowStacking}: the SWT internals it reflects on still exist in this SWT,
 * and a toggle reaches the window. A failing resolution test after an SWT upgrade names the
 * platform class to update.
 */
@Tag("swt")
class WindowStackingTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display, SWT.SHELL_TRIM);
    shell.setText("qcaret-stacking-" + System.nanoTime());
    shell.setSize(300, 200);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  private static boolean gtk4() {
    return "1".equals(System.getenv("SWT_GTK4"));
  }

  private static boolean x11() {
    return "x11".equals(System.getProperty(MainWindow.GDK_BACKEND));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3MembersResolve() {
    assertTrue(new GtkX11Stacking().supported());
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void win32MembersResolveAndATogglesSetTheTopmostStyle() throws Exception {
    Win32Stacking stacking = new Win32Stacking();
    assertTrue(stacking.supported());
    shell.open();
    stacking.setOnTop(shell, true);
    assertTrue(stacking.supported());
    assertTrue(win32Topmost(shell));
    stacking.setOnTop(shell, false);
    assertFalse(win32Topmost(shell));
  }

  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaMembersResolveAndTogglesSetTheWindowLevel() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    assertTrue(stacking.supported());
    shell.open();
    stacking.setOnTop(shell, true);
    assertEquals(3, cocoaLevel(shell));
    stacking.setOnTop(shell, false);
    assertEquals(0, cocoaLevel(shell));
    assertTrue(stacking.supported());
  }

  /** A dialog opened under a floating window floats with it and returns to normal with it. */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaRaisesADialogOpenedWhileOnTop() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    shell.open();
    stacking.setOnTop(shell, true);
    Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
    dialog.setSize(200, 100);
    dialog.open();
    Shell nested = new Shell(dialog, SWT.DIALOG_TRIM);
    nested.open();
    assertEquals(3, cocoaLevel(dialog));
    assertEquals(3, cocoaLevel(nested));
    stacking.setOnTop(shell, false);
    assertEquals(0, cocoaLevel(dialog));
    assertEquals(0, cocoaLevel(nested));
    assertTrue(stacking.raised().isEmpty());
    assertTrue(stacking.supported());
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk4AndWaylandAreUnsupported() {
    assumeTrue(gtk4() || !x11(), "GTK 3 on X11");
    assertSame(WindowStacking.NONE, WindowStacking.forPlatform());
    assertFalse(WindowStacking.forPlatform().supported());
  }

  /**
   * GTK writes {@code _NET_WM_STATE_ABOVE} when it maps the window. A toggle on a mapped window is
   * a request to the window manager, which Xvfb lacks, so each toggle here is applied while the
   * window is unmapped.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3OnX11SetsTheAboveStateEachWay() {
    assumeTrue(!gtk4() && x11(), "GTK 3 on X11");
    WindowStacking stacking = WindowStacking.forPlatform();
    assertTrue(stacking instanceof GtkX11Stacking);
    stacking.setOnTop(shell, true);
    shell.open();
    awaitAbove(true);
    shell.setVisible(false);
    stacking.setOnTop(shell, false);
    shell.setVisible(true);
    awaitAbove(false);
    shell.setVisible(false);
    stacking.setOnTop(shell, true);
    shell.setVisible(true);
    awaitAbove(true);
    assertTrue(stacking.supported());
  }

  /**
   * A window manager keeps a transient window above its parent and leaves an override-redirect
   * popup alone, so a modal dialog and a combo's list stay above a window kept on top.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3OnX11ADialogIsTransientAndAComboListOverridesTheWindowManager() {
    assumeTrue(!gtk4() && x11(), "GTK 3 on X11");
    WindowStacking.forPlatform().setOnTop(shell, true);
    shell.setLayout(new FillLayout());
    Combo combo = new Combo(shell, SWT.DROP_DOWN);
    combo.setItems("one", "two");
    shell.open();
    awaitAbove(true);
    final String parent = windowId(shell.getText());
    final Shell dialog = new Shell(shell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
    dialog.setText("qcaret-dialog-" + System.nanoTime());
    dialog.setSize(200, 100);
    dialog.open();
    final String[] transientFor = new String[1];
    boolean held =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                String id = windowId(dialog.getText());
                transientFor[0] = id == null ? "" : run("xprop", "-id", id, "WM_TRANSIENT_FOR");
                return transientFor[0].strip().endsWith(parent);
              }
            },
            BOUND);
    assertTrue(held, "WM_TRANSIENT_FOR " + transientFor[0] + ", parent " + parent);
    dialog.dispose();
    final List<String> before = topLevels();
    combo.setFocus();
    combo.setListVisible(true);
    final String[] popup = new String[1];
    held =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                for (String id : topLevels()) {
                  String info = run("xwininfo", "-id", id);
                  if (!before.contains(id) && info.contains("Map State: IsViewable")) {
                    popup[0] = info;
                    if (info.contains("Override Redirect State: yes")) {
                      return true;
                    }
                  }
                }
                return false;
              }
            },
            BOUND);
    assertTrue(held, "combo list: " + popup[0]);
    combo.setListVisible(false);
  }

  private void awaitAbove(final boolean above) {
    final String[] state = new String[1];
    boolean held =
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                String id = windowId(shell.getText());
                state[0] = id == null ? "" : run("xprop", "-id", id, "_NET_WM_STATE");
                return state[0].startsWith("_NET_WM_STATE")
                    && state[0].contains("_NET_WM_STATE_ABOVE") == above;
              }
            },
            BOUND);
    assertTrue(held, "_NET_WM_STATE above=" + above + ": " + state[0]);
  }

  /**
   * Returns the id of the top-level window whose name starts with the title (GTK appends a space),
   * or null when none does.
   */
  private static String windowId(String title) {
    for (String line : run("xwininfo", "-root", "-children").split("\n")) {
      String[] words = line.strip().split(" ", 2);
      if (words.length == 2 && words[1].startsWith("\"" + title)) {
        return words[0];
      }
    }
    return null;
  }

  /** Returns the ids of the root window's children. */
  private static List<String> topLevels() {
    List<String> ids = new ArrayList<String>();
    for (String line : run("xwininfo", "-root", "-children").split("\n")) {
      String word = line.strip().split(" ", 2)[0];
      if (word.startsWith("0x")) {
        ids.add(word);
      }
    }
    return ids;
  }

  /** Runs an X tool and returns its output. */
  private static String run(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      String out;
      try (InputStream in = process.getInputStream()) {
        out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      process.waitFor();
      return out;
    } catch (IOException e) {
      assumeTrue(false, "no " + command[0] + ": " + e.getMessage());
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  /** Reads the shell's {@code NSWindow} level through {@code objc_msgSend}. */
  private static long cocoaLevel(Shell shell) throws ReflectiveOperationException {
    Field window = Shell.class.getDeclaredField("window");
    window.setAccessible(true);
    Object nsWindow = window.get(shell);
    long id = nsWindow.getClass().getField("id").getLong(nsWindow);
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
    long selector = (Long) os.getMethod("sel_registerName", String.class).invoke(null, "level");
    return (Long) os.getMethod("objc_msgSend", long.class, long.class).invoke(null, id, selector);
  }

  /** Reads {@code WS_EX_TOPMOST} from the window's extended style. */
  private static boolean win32Topmost(Shell shell) throws ReflectiveOperationException {
    Class<?> os = Class.forName("org.eclipse.swt.internal.win32.OS");
    Method getWindowLong = os.getMethod("GetWindowLong", long.class, int.class);
    int exStyle = os.getField("GWL_EXSTYLE").getInt(null);
    int topmost = os.getField("WS_EX_TOPMOST").getInt(null);
    long handle = Control.class.getField("handle").getLong(shell);
    int style = (Integer) getWindowLong.invoke(null, handle, exStyle);
    return (style & topmost) != 0;
  }
}
