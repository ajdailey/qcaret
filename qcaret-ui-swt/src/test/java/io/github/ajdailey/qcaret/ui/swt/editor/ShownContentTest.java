package io.github.ajdailey.qcaret.ui.swt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.text.ControlChars;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.HTMLTransfer;
import org.eclipse.swt.dnd.RTFTransfer;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Copy from a read-only {@link ShownContent} pane on a real display: the widget lays out
 * placeholders, and its copy key and selection clipboard put the original chars in every format.
 */
@Tag("swt")
class ShownContentTest {
  /** ESC at the start, SOH inside, U+0081 at the end of line 0. */
  private static final String TEXT = "\u001bab\u0001cd\u0081\nnext";

  private Display display;
  private Shell shell;
  private StyledText widget;
  private Clipboard clipboard;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(null);
    widget = new StyledText(shell, SWT.MULTI | SWT.READ_ONLY);
    widget.setSize(400, 100);
    ShownContent.install(widget);
    widget.setText(TEXT);
    clipboard = new Clipboard(display);
    shell.open();
    widget.setFocus();
  }

  @AfterEach
  void close() {
    clipboard.dispose();
    display.dispose();
  }

  /** Sends the widget its copy key. */
  private void copyKey() {
    Event down = new Event();
    down.keyCode = 'c';
    down.character = 'c';
    down.stateMask = SWT.MOD1;
    widget.notifyListeners(SWT.KeyDown, down);
  }

  private String contents(Transfer transfer, int type) {
    Object value = clipboard.getContents(transfer, type);
    return value instanceof String string ? string : null;
  }

  @Test
  void theLineLaysOutPlaceholders() {
    assertEquals(ControlChars.shown("\u001bab\u0001cd\u0081"), widget.getLine(0));
  }

  @Test
  void theCopyKeyPutsTheOriginalCharsInEveryFormat() {
    int[][] ranges = {{0, 2}, {2, 5}, {5, 7}, {0, 12}};
    for (int[] range : ranges) {
      widget.setSelection(range[0], range[1]);
      copyKey();
      String expected = TEXT.substring(range[0], range[1]);
      String plain = contents(TextTransfer.getInstance(), DND.CLIPBOARD);
      assertEquals(expected, plain.replace("\r\n", "\n"), range[0] + "-" + range[1]);
      String rtf = contents(RTFTransfer.getInstance(), DND.CLIPBOARD);
      assertFormat(rtf, expected, "\\u-4?");
      String html = contents(HTMLTransfer.getInstance(), DND.CLIPBOARD);
      assertFormat(html, expected, String.valueOf(ControlChars.PLACEHOLDER));
    }
    assertEquals(ControlChars.shown("\u001bab\u0001cd\u0081"), widget.getLine(0));
  }

  /** Asserts that a rich format holds the selection's control chars and no placeholder. */
  private static void assertFormat(String format, String expected, String placeholder) {
    assertTrue(format != null, "format missing");
    assertFalse(format.contains(placeholder), format);
    for (int i = 0; i < expected.length(); i++) {
      char c = expected.charAt(i);
      if (c == 0x01 || c == 0x1b) {
        assertTrue(format.indexOf(c) >= 0, format);
      }
    }
  }

  @Test
  void theSelectionClipboardGetsTheOriginalChars() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "only GTK has a selection clipboard");
    widget.setSelection(1, 4);
    Event up = new Event();
    up.button = 1;
    widget.notifyListeners(SWT.MouseUp, up);
    assertEquals("ab\u0001", contents(TextTransfer.getInstance(), DND.SELECTION_CLIPBOARD));
  }
}
