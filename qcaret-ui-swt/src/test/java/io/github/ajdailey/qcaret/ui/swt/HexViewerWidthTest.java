package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.hex.HexRow;
import io.github.ajdailey.qcaret.ui.swt.hex.HexPane;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.util.BitSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The bottom panel of a hex pane at a Hex Viewer window's minimum width: the longest find message
 * wraps inside the pane's width, and Next and the status line's Size stay inside it. The width is
 * the viewer shell's {@code getMinimumSize} less its trim (win32 and cocoa report their native
 * minimum unless the window sets a larger one; GTK reports what the window sets). A pane of that
 * width sits in a shell with no layout, a tab's pane as well as a viewer's.
 */
@Tag("swt")
class HexViewerWidthTest {
  private static final int HEIGHT = 400;

  private Display display;
  private Shell owner;
  private Font font;

  @BeforeEach
  void open() {
    display = new Display();
    owner = new Shell(display);
    font =
        new Font(
            display,
            PlatformTraits.PLATFORM.monospaceFamily(),
            PlatformTraits.PLATFORM.monospaceSize(),
            SWT.NORMAL);
  }

  @AfterEach
  void close() {
    owner.dispose();
    font.dispose();
    display.dispose();
  }

  @Test
  void theFindBarAndStatusFitAViewerAtItsMinimumWidth() {
    HexWindow viewer =
        new HexWindow(owner, "blob.bin", new Bytes(), Palette.LIGHT, font, keymap(), new Timers());
    Shell window = viewer.shell();
    int width = Math.max(1, window.getMinimumSize().x - window.computeTrim(0, 0, 0, 0).width);
    for (boolean inWindow : new boolean[] {true, false}) {
      Shell shell = new Shell(display);
      try {
        shell.setLayout(null);
        HexPane pane = pane(shell, inWindow);
        pane.control().setBounds(0, 0, width, HEIGHT);
        shell.open();
        String what = (inWindow ? "viewer" : "tab") + " pane at " + width + " px: ";
        pane.showStatus(
            UiText.HEX_STATUS_SELECTION.format(
                "0x0000000080000000 (2,147,483,648)",
                "2,147,483,648",
                "0x00000000FFFFFFFF (4,294,967,295)"));
        pane.showFind("4D 5A", true);
        pane.showMessage(longestMessage(pane.findMessage()));

        Label message = pane.findMessage();
        assertTrue(message.getVisible(), what + "message shown");
        fits(pane, message, what + "message");
        assertTrue(
            message.getSize().y >= message.computeSize(message.getSize().x, SWT.DEFAULT).y,
            what + "every message line shows: " + message.getSize());
        Button next = button(pane, UiText.BUTTON_NEXT.text());
        assertNotNull(next, what + "Next");
        fits(pane, next, what + "Next");
        assertTrue(
            next.getSize().x >= next.computeSize(SWT.DEFAULT, SWT.DEFAULT).x,
            what + "Next whole: " + next.getSize());
        Label status = pane.status();
        fits(pane, status, what + "status");
        assertTrue(
            status.getSize().y >= status.computeSize(status.getSize().x, SWT.DEFAULT).y,
            what + "every status line, Size included, shows: " + status.getSize());
      } finally {
        shell.dispose();
      }
    }
  }

  /** Asserts that a control lies inside the pane's width and height. */
  private void fits(HexPane pane, Control control, String what) {
    Rectangle bounds = display.map(control.getParent(), pane.control(), control.getBounds());
    Rectangle area = pane.control().getClientArea();
    assertTrue(bounds.width > 0 && bounds.height > 0, what + " has a size: " + bounds);
    assertTrue(
        bounds.x >= 0
            && bounds.x + bounds.width <= area.width
            && bounds.y >= 0
            && bounds.y + bounds.height <= area.height,
        what + " " + bounds + " inside " + area);
  }

  /** Returns the widest find message as the message label measures it. */
  private static String longestMessage(Label message) {
    List<String> messages =
        List.of(
            UiText.HEX_PATTERN_NOT_HEX.format(1000),
            UiText.HEX_PATTERN_ODD_DIGITS.format(1000),
            UiText.HEX_PATTERN_WILDCARDS_ONLY.text(),
            UiText.HEX_PATTERN_EMPTY.text(),
            UiText.HEX_FIND_WRAPPED.text(),
            UiText.HEX_FIND_NOT_FOUND.text());
    String longest = "";
    int widest = -1;
    for (String text : messages) {
      message.setText(text);
      int width = message.computeSize(SWT.DEFAULT, SWT.DEFAULT).x;
      if (width > widest) {
        widest = width;
        longest = text;
      }
    }
    return longest;
  }

  private static Button button(HexPane pane, String text) {
    for (Control child : pane.findBar().getChildren()) {
      if (child instanceof Button button && text.equals(button.getText())) {
        return button;
      }
    }
    return null;
  }

  private HexPane pane(Shell shell, boolean inWindow) {
    return new HexPane(
        shell,
        new Bytes(),
        Palette.LIGHT,
        font,
        keymap(),
        new Timers(),
        new HexPane.Host() {
          @Override
          public void askGoToOffset(
              Shell parent, Predicate<String> valid, Consumer<String> answer) {
            // Not used.
          }

          @Override
          public void close() {
            // Not used.
          }
        },
        inWindow);
  }

  private static Keymap keymap() {
    return Keymap.defaults(PlatformTraits.PLATFORM.macKeys());
  }

  /** A controller with no bytes that ignores input. */
  private static final class Bytes implements HexInput {
    @Override
    public void attach(HexView view) {
      // Not used.
    }

    @Override
    public void detach(HexView view) {
      // Not used.
    }

    @Override
    public HexRow row(long row) {
      return new HexRow(0, "", new byte[0], new BitSet());
    }

    @Override
    public void resized(int rows) {
      // Not used.
    }

    @Override
    public void scrolled(int selection) {
      // Not used.
    }

    @Override
    public void scrollBy(long rows) {
      // Not used.
    }

    @Override
    public void key(HexKey key, boolean extend) {
      // Not used.
    }

    @Override
    public void switchColumn() {
      // Not used.
    }

    @Override
    public void clicked(long offset, boolean textColumn, boolean extend) {
      // Not used.
    }

    @Override
    public void dragged(long offset) {
      // Not used.
    }

    @Override
    public void selectAll() {
      // Not used.
    }

    @Override
    public void focusChanged(boolean focused) {
      // Not used.
    }

    @Override
    public void copy(HexCopy kind) {
      // Not used.
    }

    @Override
    public void goToOffset() {
      // Not used.
    }

    @Override
    public void openFind() {
      // Not used.
    }

    @Override
    public void find(String text, boolean hex, boolean forward) {
      // Not used.
    }

    @Override
    public void cancel() {
      // Not used.
    }

    @Override
    public void noticeAction() {
      // Not used.
    }
  }

  /** Runs nothing: these tests start no drag. */
  private static final class Timers implements UiDispatcher {
    @Override
    public void asyncExec(Runnable task) {
      // Not used.
    }

    @Override
    public void timerExec(int delayMillis, Runnable task) {
      // Not used.
    }

    @Override
    public void cancelTimer(Runnable task) {
      // Not used.
    }

    @Override
    public boolean isUiThread() {
      return true;
    }
  }
}
