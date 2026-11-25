package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.ui.swt.EventLoop;
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
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
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
    return "x11".equals(System.getProperty(PlatformTraits.GDK_BACKEND));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk3MembersResolve() {
    assertTrue(new GtkX11Stacking().supported());
  }

  /** GTK 3 explains a disabled Always on Top item; only GTK's stacking can be unsupported. */
  @Test
  @EnabledOnOs(OS.LINUX)
  void gtkStackingExplainsTheDisabledItem() {
    assumeTrue(!gtk4(), "SWT drops menu item tool tips on GTK 4");
    Menu menu = new Menu(shell, SWT.DROP_DOWN);
    MenuItem onX11 = new MenuItem(menu, SWT.CHECK);
    new GtkX11Stacking().explainUnsupported(onX11);
    assertEquals(UiText.ALWAYS_ON_TOP_UNSUPPORTED.text(), onX11.getToolTipText());
    MenuItem managed = new MenuItem(menu, SWT.CHECK);
    new WindowManagerStacking().explainUnsupported(managed);
    assertEquals(UiText.ALWAYS_ON_TOP_UNSUPPORTED.text(), managed.getToolTipText());
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

  /**
   * Each SWT member {@link CocoaStacking} reflects on, by name, so an upgrade names the one lost.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaReflectedMembersExist() throws Exception {
    ClassLoader loader = Shell.class.getClassLoader();
    Class<?> nsWindow = Class.forName("org.eclipse.swt.internal.cocoa.NSWindow", false, loader);
    Class<?> id = Class.forName("org.eclipse.swt.internal.cocoa.id", false, loader);
    assertEquals(nsWindow, Shell.class.getDeclaredField("window").getType(), "Shell.window");
    assertNotNull(nsWindow.getMethod("setLevel", long.class), "setLevel");
    assertNotNull(nsWindow.getMethod("removeChildWindow", nsWindow), "removeChildWindow");
    assertNotNull(nsWindow.getMethod("addChildWindow", nsWindow, long.class), "addChildWindow");
    assertNotNull(nsWindow.getMethod("orderFront", id), "orderFront");
    assertTrue(new CocoaStacking().supported());
  }

  /**
   * The title bar's minimize button on a tool window took its owner off screen; the Iconify filter
   * brings the owner back and the tool window rejoins its children when restored.
   */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaKeepsTheOwnerWhenTheTitleBarMinimizesAToolWindow() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    shell.open();
    final Shell tool = new Shell(shell, SWT.SHELL_TRIM);
    tool.setSize(200, 120);
    tool.open();
    assumeTrue(occlusion(shell, true), "the owner never shows on screen (screen locked?)");
    cocoaSend(tool, "performMiniaturize:");
    awaitCocoa(tool, "isMiniaturized", true);
    awaitOcclusion(tool, false);
    awaitOcclusion(shell, true);
    assertEquals(0, cocoaParentId(tool), "out of the owner's children while minimized");
    tool.setMinimized(false);
    awaitCocoa(tool, "isMiniaturized", false);
    assertEquals(cocoaId(shell), cocoaParentId(tool), "a child window again once restored");
    assertTrue(stacking.supported());
  }

  /** Sends a one-argument selector, with a nil argument, to the shell's {@code NSWindow}. */
  private static void cocoaSend(Shell shell, String selector) throws ReflectiveOperationException {
    long id = cocoaId(shell);
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
    long sel = (Long) os.getMethod("sel_registerName", String.class).invoke(null, selector);
    os.getMethod("objc_msgSend", long.class, long.class, long.class).invoke(null, id, sel, 0L);
  }

  /** Minimize on a tool window took its owner off screen until the tool window was restored. */
  @Test
  @EnabledOnOs(OS.MAC)
  void cocoaMinimizesAToolWindowWithoutItsOwner() throws Exception {
    CocoaStacking stacking = new CocoaStacking();
    shell.open();
    final Shell tool = new Shell(shell, SWT.SHELL_TRIM);
    tool.setSize(200, 120);
    tool.open();
    assertEquals(cocoaId(shell), cocoaParentId(tool), "a child window of its owner");
    assumeTrue(occlusion(shell, true), "the owner never shows on screen (screen locked?)");
    stacking.minimize(tool);
    awaitCocoa(tool, "isMiniaturized", true);
    // NSWindowOcclusionStateVisible: isVisible stays true while AppKit hides the owner.
    awaitOcclusion(tool, false);
    assertTrue(cocoaVisibleOnScreen(shell), "the owner stays on screen");
    assertFalse(cocoaFlag(shell, "isMiniaturized"));
    assertEquals(0, cocoaParentId(tool), "out of the owner's children while minimized");
    tool.setMinimized(false);
    awaitCocoa(tool, "isMiniaturized", false);
    assertEquals(cocoaId(shell), cocoaParentId(tool), "a child window again once restored");
    assertTrue(stacking.supported());
  }

  /** Waits for a no-argument boolean {@code NSWindow} method to return a value. */
  private void awaitCocoa(final Shell target, final String method, final boolean value) {
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                try {
                  return cocoaFlag(target, method) == value;
                } catch (ReflectiveOperationException e) {
                  throw new IllegalStateException(e);
                }
              }
            },
            BOUND),
        method + " " + value);
  }

  private void awaitOcclusion(Shell target, boolean visible) {
    assertTrue(occlusion(target, visible), "occlusion visible " + visible);
  }

  /** Waits for the window's on-screen state; false when it does not reach it in time. */
  private boolean occlusion(final Shell target, final boolean visible) {
    return EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            try {
              return cocoaVisibleOnScreen(target) == visible;
            } catch (ReflectiveOperationException e) {
              throw new IllegalStateException(e);
            }
          }
        },
        BOUND);
  }

  /** Reads {@code NSWindowOcclusionStateVisible} from {@code occlusionState}. */
  private static boolean cocoaVisibleOnScreen(Shell shell) throws ReflectiveOperationException {
    long id = cocoaId(shell);
    Class<?> os = Class.forName("org.eclipse.swt.internal.cocoa.OS");
    long selector =
        (Long) os.getMethod("sel_registerName", String.class).invoke(null, "occlusionState");
    long state =
        (Long) os.getMethod("objc_msgSend", long.class, long.class).invoke(null, id, selector);
    return (state & 2) != 0;
  }

  private static Object cocoaWindow(Shell shell) throws ReflectiveOperationException {
    Field window = Shell.class.getDeclaredField("window");
    window.setAccessible(true);
    return window.get(shell);
  }

  private static long cocoaId(Shell shell) throws ReflectiveOperationException {
    Object nsWindow = cocoaWindow(shell);
    return nsWindow.getClass().getField("id").getLong(nsWindow);
  }

  /** Returns the id of the shell's parent {@code NSWindow}, or 0 with none. */
  private static long cocoaParentId(Shell shell) throws ReflectiveOperationException {
    Object nsWindow = cocoaWindow(shell);
    Object parent = nsWindow.getClass().getMethod("parentWindow").invoke(nsWindow);
    return parent == null ? 0 : parent.getClass().getField("id").getLong(parent);
  }

  private static boolean cocoaFlag(Shell shell, String method) throws ReflectiveOperationException {
    Object nsWindow = cocoaWindow(shell);
    return (Boolean) nsWindow.getClass().getMethod(method).invoke(nsWindow);
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void gtk4AndWaylandAreUnsupported() {
    assumeTrue(gtk4() || !x11(), "GTK 3 on X11");
    assertTrue(PlatformTraits.PLATFORM.stacking() instanceof WindowManagerStacking);
    assertFalse(PlatformTraits.PLATFORM.stacking().supported());
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
    WindowStacking stacking = PlatformTraits.PLATFORM.stacking();
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
    PlatformTraits.PLATFORM.stacking().setOnTop(shell, true);
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
