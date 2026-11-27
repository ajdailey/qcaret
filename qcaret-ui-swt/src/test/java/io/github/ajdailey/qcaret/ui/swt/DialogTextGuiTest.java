package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.PaletteEntry;
import io.github.ajdailey.qcaret.app.view.PaletteInput;
import io.github.ajdailey.qcaret.ui.swt.platform.AccessibleName;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Dialog text under Xvfb: an {@code &} in an error message, the accessible names of a history
 * field, and the Keys column of Help > Keyboard Shortcuts and the command palette whole at the
 * fitted width on a 1100 x 700 screen.
 */
@Tag("swt")
class DialogTextGuiTest {
  private static final Duration BOUND = Duration.ofSeconds(20);
  private static final Rectangle WIDE = new Rectangle(0, 0, 1100, 700);

  private static final ClipboardService CLIPBOARD =
      new ClipboardService() {
        @Override
        public void setText(String text) {
          // Not read.
        }

        @Override
        public String clipboardText() {
          return "";
        }
      };

  private Display display;
  private Shell shell;
  private Keymap keymap;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setBounds(0, 0, 1100, 700);
    shell.open();
    keymap = Keymap.defaults(PlatformTraits.PLATFORM.macKeys());
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void anAmpersandInAnErrorShowsAsItself() {
    ErrorDialog dialog = new ErrorDialog(shell, "mock", "Cannot open a&b.txt: permission denied");
    Label label = null;
    for (Control child : dialog.shell().getChildren()) {
      if (child instanceof Label shown) {
        label = shown;
      }
    }
    assertNotNull(label);
    assertEquals("Cannot open a&&b.txt: permission denied", label.getText());
  }

  @Test
  void aHistoryFieldTakesItsLabelsNameAndItsArrowIsNamed() {
    Shell dialog = new Shell(shell);
    dialog.setLayout(new GridLayout(2, false));
    new Label(dialog, SWT.NONE).setText(UiText.FIND_LABEL.text());
    HistoryField field = new HistoryField(dialog, 200);
    assertEquals("Find", name(field.text()));
    assertEquals(UiText.FIND_HISTORY.text(), name(field.arrow()));
    assertEquals("Replace", HistoryField.name("&Replace: "));
  }

  /** The name a screen reader reads, through the control's accessible listener. */
  private static String name(Control control) {
    AccessibleName name = AccessibleName.of(control);
    assertNotNull(name, control.toString());
    AccessibleEvent event = new AccessibleEvent(control.getAccessible());
    event.childID = ACC.CHILDID_SELF;
    name.getName(event);
    return event.result;
  }

  @Test
  void shortcutsShowEveryKeyAtTheFittedWidth() {
    ShortcutsWindow shortcuts = new ShortcutsWindow(shell, keymap, CLIPBOARD);
    shortcuts.open(WIDE);
    assertWhole(shortcuts.shell(), shortcuts.table());
  }

  @Test
  void thePaletteShowsEveryKeyAtTheFittedWidth() {
    final List<PaletteEntry> entries = new ArrayList<PaletteEntry>();
    for (CommandId command : CommandId.values()) {
      String keys = keymap.keysText(command);
      if (!keys.isEmpty()) {
        entries.add(
            new PaletteEntry(
                command, command.qualifiedTitle(), command.group().title(), keys, true, null));
      }
    }
    CommandPaletteWindow palette =
        new CommandPaletteWindow(
            shell,
            new PaletteInput() {
              @Override
              public List<PaletteEntry> filter(String query) {
                return entries;
              }

              @Override
              public void run(CommandId command) {
                // Not chosen.
              }

              @Override
              public void runMacro(String name) {
                // Not chosen.
              }
            },
            new SwtUiDispatcher(display));
    palette.open(WIDE);
    assertWhole(palette.shell(), palette.table());
  }

  /** Every cell of every column shows its whole text, and the dialog fits the screen. */
  private void assertWhole(Shell dialog, Table table) {
    settle(dialog, table);
    assertTrue(dialog.getSize().x <= WIDE.width, "width " + dialog.getSize().x);
    GC gc = new GC(table);
    try {
      for (TableItem item : table.getItems()) {
        for (int column = 0; column < table.getColumnCount(); column++) {
          int text = gc.textExtent(item.getText(column)).x;
          int shown = TableFit.shownWidth(item, column);
          assertTrue(
              text <= shown, "'" + item.getText(column) + "' needs " + text + ", shows " + shown);
          assertNull(TableFit.clippedText(item, column));
        }
      }
    } finally {
      gc.dispose();
    }
  }

  /**
   * Runs the event loop until the table's width holds and its columns and rows have bounds; GTK 4
   * reports row bounds only after a later layout pass.
   */
  private void settle(final Shell dialog, final Table table) {
    final int[] last = {-1};
    final int[] same = {0};
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                int width = table.getClientArea().width;
                same[0] = width > 0 && width == last[0] ? same[0] + 1 : 0;
                last[0] = width;
                return dialog.isVisible()
                    && same[0] >= 5
                    && table.getColumn(0).getWidth() > 0
                    && table.getItem(0).getBounds(0).width > 0;
              }
            },
            BOUND),
        "table never laid out");
  }
}
