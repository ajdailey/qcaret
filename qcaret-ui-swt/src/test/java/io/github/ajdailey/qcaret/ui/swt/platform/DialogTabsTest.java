package io.github.ajdailey.qcaret.ui.swt.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.ui.swt.EventLoop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link Win32DialogTabs} and {@link NativeDialogTabs} on any platform: which folder each
 * platform's {@link PlatformTraits#dialogTabs} creates, the dark colors, and selection through the
 * interface. On win32, keys posted to both folders must move the selection the same way.
 */
@Tag("swt")
class DialogTabsTest {
  private static final Duration BOUND = Duration.ofSeconds(10);
  private static final Duration KEY_BOUND = Duration.ofSeconds(1);

  private Display display;
  private Shell owner;

  @BeforeEach
  void open() {
    display = new Display();
    display.setData(DialogColors.NATIVE_DARK, Boolean.TRUE);
    owner = new Shell(display);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void win32AfterADarkStartColorsAFlatCTabFolderAndEveryPage() {
    Win32DialogColors colors = Win32DialogColors.INSTANCE;
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    colors.apply(shell);
    DialogTabs tabs = new Win32Traits().dialogTabs(shell);
    assertSame(Win32DialogTabs.class, tabs.getClass());
    List<Label> labels = new ArrayList<Label>();
    for (int i = 0; i < 3; i++) {
      Composite page = tabs.add("Mock " + i);
      page.setLayout(new FillLayout());
      Label label = new Label(page, SWT.NONE);
      label.setText("mock label " + i);
      labels.add(label);
    }
    shell.open();
    CTabFolder folder = (CTabFolder) tabs.control();
    assertTrue((folder.getStyle() & SWT.FLAT) != 0);
    assertTrue((folder.getStyle() & SWT.BORDER) == 0, "no frame around the page");
    assertEquals(Win32DialogColors.WINDOW.getRGB(), folder.getBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), folder.getForeground().getRGB());
    assertEquals(Win32DialogColors.FIELD.getRGB(), folder.getSelectionBackground().getRGB());
    assertEquals(Win32DialogColors.TEXT.getRGB(), folder.getSelectionForeground().getRGB());
    assertTrue(
        Contrast.ratio(Win32DialogColors.TEXT.getRGB(), Win32DialogColors.WINDOW.getRGB()) >= 4.5);
    assertTrue(
        Contrast.ratio(Win32DialogColors.TEXT.getRGB(), Win32DialogColors.FIELD.getRGB()) >= 4.5);
    for (Label label : labels) {
      assertEquals(Win32DialogColors.WINDOW.getRGB(), label.getParent().getBackground().getRGB());
      assertEquals(Win32DialogColors.TEXT.getRGB(), label.getForeground().getRGB());
    }
    assertEquals(0, tabs.selection(), "the first tab added is selected");
  }

  @Test
  void win32AfterALightStartCreatesTheNativeFolder() {
    display.setData(DialogColors.NATIVE_DARK, null);
    DialogTabs tabs = new Win32Traits().dialogTabs(owner);
    assertSame(NativeDialogTabs.class, tabs.getClass());
    assertSame(TabFolder.class, tabs.control().getClass());
  }

  @Test
  void gtkAndCocoaCreateTheNativeFolderAfterADarkStart() {
    PlatformTraits[] natives = {new Gtk3Traits(), new Gtk4Traits(), new CocoaTraits()};
    for (PlatformTraits traits : natives) {
      DialogTabs tabs = traits.dialogTabs(owner);
      assertSame(NativeDialogTabs.class, tabs.getClass(), traits.name());
      assertSame(TabFolder.class, tabs.control().getClass(), traits.name());
    }
  }

  /**
   * Both folders: the first tab is selected, {@code select} sends no selection event, and
   * Ctrl+PageDown from a page's field selects the next tab and runs the listener once.
   */
  @Test
  void selectionAndListenerBehaveTheSameOnBothFolders() {
    List<DialogTabs> both = new ArrayList<DialogTabs>();
    both.add(new Win32DialogTabs(newShell()));
    both.add(new NativeDialogTabs(newShell()));
    for (final DialogTabs tabs : both) {
      String name = tabs.getClass().getSimpleName();
      List<Text> fields = fill(tabs);
      final int[] heard = new int[1];
      tabs.addSelectionListener(
          new Runnable() {
            @Override
            public void run() {
              heard[0]++;
            }
          });
      tabs.control().getShell().open();
      assertEquals(3, tabs.count(), name);
      assertEquals(0, tabs.selection(), name);
      tabs.select(2);
      assertEquals(2, tabs.selection(), name);
      tabs.select(0);
      settle();
      assertEquals(0, heard[0], name + ": select notifies no listener");
      Event key = new Event();
      key.keyCode = SWT.PAGE_DOWN;
      key.stateMask = SWT.CTRL;
      assertTrue(fields.get(0).traverse(SWT.TRAVERSE_PAGE_NEXT, key), name);
      assertTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return heard[0] == 1;
                }
              },
              BOUND),
          name + ": listener");
      assertEquals(1, tabs.selection(), name);
    }
  }

  /**
   * On win32, Ctrl+PageDown, Ctrl+PageUp, Ctrl+Tab and Ctrl+Shift+Tab posted while a page's field
   * has focus move the {@link Win32DialogTabs} selection as they move the native folder's, and the
   * mnemonics of a button on the shown page and on a hidden page press the same buttons. The
   * results are written to {@code target/diagnostics/dialog-tabs/keys.txt}. Skips where posted keys
   * do not reach the native folder.
   */
  @Test
  void win32KeysMoveBothFoldersAlike() throws IOException {
    assumeTrue("win32".equals(SWT.getPlatform()), "the folder pair differs on win32 only");
    List<String> nativeMoves = keyMoves(new NativeDialogTabs(newShell()));
    List<String> darkMoves = keyMoves(new Win32DialogTabs(newShell()));
    Path dir = Files.createDirectories(Path.of("target", "diagnostics", "dialog-tabs"));
    Files.writeString(
        dir.resolve("keys.txt"),
        "keys: Ctrl+PageDown, Ctrl+PageUp, Ctrl+Tab, Ctrl+Shift+Tab, Alt+K (shown page),"
            + " Alt+J (hidden page), from the first tab\n"
            + "native TabFolder: "
            + nativeMoves
            + "\nWin32DialogTabs: "
            + darkMoves
            + "\n");
    assumeTrue(nativeMoves.get(0).equals("1"), "posted keys reach the native folder");
    assertEquals(nativeMoves, darkMoves);
  }

  /**
   * Posts each key with focus in the first tab's field; returns the selection after each, then the
   * buttons each mnemonic pressed.
   */
  private List<String> keyMoves(DialogTabs tabs) {
    final List<Text> fields = fill(tabs);
    final List<String> pressed = new ArrayList<String>();
    button(tabs.page(0), "&Knock", pressed);
    button(tabs.page(1), "&Jump", pressed);
    Shell shell = tabs.control().getShell();
    shell.setSize(400, 300);
    shell.open();
    shell.forceActive();
    int[][] keys = {
      {SWT.CTRL, SWT.PAGE_DOWN},
      {SWT.CTRL, SWT.PAGE_UP},
      {SWT.CTRL, '\t'},
      {SWT.CTRL | SWT.SHIFT, '\t'},
    };
    List<String> moves = new ArrayList<String>();
    for (int[] key : keys) {
      tabs.select(0);
      fields.get(0).forceFocus();
      settle();
      final int before = tabs.selection();
      final DialogTabs watched = tabs;
      post(key[0], key[1]);
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return watched.selection() != before;
            }
          },
          KEY_BOUND);
      moves.add(String.valueOf(tabs.selection()));
    }
    for (char mnemonic : new char[] {'k', 'j'}) {
      tabs.select(0);
      fields.get(0).forceFocus();
      settle();
      final int before = pressed.size();
      post(SWT.ALT, mnemonic);
      EventLoop.runUntil(
          display,
          new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
              return pressed.size() != before;
            }
          },
          KEY_BOUND);
      moves.add(mnemonic + "->" + pressed.subList(before, pressed.size()));
    }
    shell.dispose();
    return moves;
  }

  private void post(int modifiers, int key) {
    List<Integer> held = new ArrayList<Integer>();
    if ((modifiers & SWT.CTRL) != 0) {
      held.add(SWT.CTRL);
    }
    if ((modifiers & SWT.SHIFT) != 0) {
      held.add(SWT.SHIFT);
    }
    if ((modifiers & SWT.ALT) != 0) {
      held.add(SWT.ALT);
    }
    for (int modifier : held) {
      postKey(SWT.KeyDown, modifier, '\0');
    }
    if (key == '\t' || Character.isLetter(key)) {
      postKey(SWT.KeyDown, 0, (char) key);
      postKey(SWT.KeyUp, 0, (char) key);
    } else {
      postKey(SWT.KeyDown, key, '\0');
      postKey(SWT.KeyUp, key, '\0');
    }
    for (int i = held.size() - 1; i >= 0; i--) {
      postKey(SWT.KeyUp, held.get(i), '\0');
    }
  }

  private void postKey(int type, int keyCode, char character) {
    Event event = new Event();
    event.type = type;
    event.keyCode = keyCode;
    event.character = character;
    display.post(event);
  }

  private static void button(Composite page, final String text, final List<String> pressed) {
    page.setLayout(new RowLayout());
    Button button = new Button(page, SWT.PUSH);
    button.setText(text);
    button.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            pressed.add(text);
          }
        });
  }

  private Shell newShell() {
    Shell shell = new Shell(owner, SWT.DIALOG_TRIM);
    shell.setLayout(new FillLayout());
    return shell;
  }

  /** Adds three tabs, each holding a field. */
  private static List<Text> fill(DialogTabs tabs) {
    List<Text> fields = new ArrayList<Text>();
    for (int i = 0; i < 3; i++) {
      Composite page = tabs.add("Mock " + i);
      page.setLayout(new FillLayout());
      fields.add(new Text(page, SWT.SINGLE | SWT.BORDER));
    }
    Control folder = tabs.control();
    folder.getShell().layout(true, true);
    return fields;
  }

  /** Dispatches pending events without sleeping. */
  private void settle() {
    long deadline = System.nanoTime() + BOUND.toNanos();
    while (System.nanoTime() < deadline && display.readAndDispatch()) {
      // Runs queued work.
    }
  }
}
