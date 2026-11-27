package io.github.ajdailey.qcaret.ui.swt.hex;

import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexScroll;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;

/**
 * A {@link HexView}: the notice bar, the rows ({@link HexCanvas}), the find bar ({@link
 * HexFindBar}) and the status line, top to bottom. A tab's hex pane and a Hex Viewer window each
 * hold one. The busy cursor counts overlapping operations. Disposing the pane detaches it from its
 * controller. Call on the UI thread.
 */
public final class HexPane implements HexView {
  /** The window around the pane. */
  public interface Host {
    /**
     * Opens the Go to Offset prompt.
     *
     * @param parent the shell it is modal over
     * @param valid accepts the text OK submits
     * @param answer receives the text
     */
    void askGoToOffset(Shell parent, Predicate<String> valid, Consumer<String> answer);

    /** Closes the Hex Viewer window holding the pane; a tab's pane does nothing. */
    void close();
  }

  private final Composite root;
  private final Composite notice;
  private final Label noticeText;
  private final Button noticeButton;
  private final HexCanvas rows;
  private final HexFindBar find;
  private final Label status;
  private final Host host;
  private int busy;

  /**
   * Creates the pane in a parent.
   *
   * @param parent the parent
   * @param input the view's controller
   * @param palette the colors
   * @param font the editor font, which the caller owns
   * @param keymap the key bindings
   * @param timers runs the drag autoscroll
   * @param host the window's prompts
   * @param window true in a Hex Viewer window
   */
  public HexPane(
      Composite parent,
      final HexInput input,
      Palette palette,
      Font font,
      Keymap keymap,
      UiDispatcher timers,
      Host host,
      boolean window) {
    this.host = host;
    root = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    layout.verticalSpacing = 0;
    root.setLayout(layout);
    notice = new Composite(root, SWT.NONE);
    notice.setLayout(new GridLayout(2, false));
    GridData noticeData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    noticeData.exclude = true;
    notice.setLayoutData(noticeData);
    notice.setVisible(false);
    noticeText = new Label(notice, SWT.WRAP);
    noticeText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    noticeButton = new Button(notice, SWT.PUSH);
    noticeButton.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    noticeButton.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.noticeAction();
          }
        });
    final HexFindBar[] bar = new HexFindBar[1];
    rows =
        new HexCanvas(
            root,
            input,
            palette,
            font,
            keymap,
            timers,
            new HexGestures.Commands() {
              @Override
              public void openFind() {
                input.openFind();
              }

              @Override
              public void findAgain(boolean forward) {
                bar[0].find(forward);
              }

              @Override
              public void close() {
                HexPane.this.host.close();
              }
            },
            window);
    rows.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    find =
        new HexFindBar(
            root,
            input,
            new Runnable() {
              @Override
              public void run() {
                focus();
              }
            });
    bar[0] = find;
    status = new Label(root, SWT.NONE);
    GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
    statusData.horizontalIndent = 4;
    status.setLayoutData(statusData);
    root.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.detach(HexPane.this);
          }
        });
  }

  /** Returns the pane's top control. */
  public Composite control() {
    return root;
  }

  /** Returns the canvas the rows paint on. */
  public Canvas canvas() {
    return rows.canvas();
  }

  /** Returns the notice bar, the find bar and the status line, which follow the native theme. */
  public Control[] chrome() {
    return new Control[] {notice, find.control(), status};
  }

  /** Returns the status line. */
  public Label status() {
    return status;
  }

  /** Returns the notice bar. */
  public Composite notice() {
    return notice;
  }

  /** Returns the notice bar's button. */
  public Button noticeButton() {
    return noticeButton;
  }

  /** Returns the find bar. */
  public Composite findBar() {
    return find.control();
  }

  /** Returns the find bar's message. */
  public Label findMessage() {
    return find.message();
  }

  /** Returns the rows' context menu, or null before it first showed. */
  public Menu menu() {
    return rows.menu();
  }

  /**
   * Returns the x of a column's left edge in the canvas.
   *
   * @param column a column of the layout's {@link HexRows}
   * @return pixels
   */
  public int columnX(int column) {
    return rows.columnX(column);
  }

  /** Returns the layout shown. */
  public HexRows rows() {
    return rows.rows();
  }

  /** Returns the width a full row and the scroll bar need. */
  public int preferredWidth() {
    return rows.preferredWidth();
  }

  /** Returns the color the rows paint their background in. */
  public RGB background() {
    return rows.background().getRGB();
  }

  /** Returns the row height in pixels. */
  public int lineHeight() {
    return rows.grid().lineHeight();
  }

  /** Paints with a palette's colors. */
  public void setPalette(Palette palette) {
    if (!root.isDisposed()) {
      rows.setPalette(palette);
    }
  }

  /** Paints in another font; the caller disposes the old one after this returns. */
  public void setFont(Font font) {
    if (!root.isDisposed()) {
      rows.setFont(font);
    }
  }

  /** Takes changed key bindings. */
  public void setKeymap(Keymap keymap) {
    rows.setKeymap(keymap);
  }

  @Override
  public void showLayout(HexRows value) {
    if (!root.isDisposed()) {
      rows.showLayout(value);
    }
  }

  @Override
  public void showScroll(HexScroll scroll) {
    if (!root.isDisposed()) {
      rows.showScroll(scroll);
    }
  }

  @Override
  public void showSelection(HexSelection selection) {
    if (!root.isDisposed()) {
      rows.showSelection(selection);
    }
  }

  @Override
  public void redraw() {
    if (!root.isDisposed()) {
      rows.redraw();
    }
  }

  @Override
  public void setDimmed(boolean dimmed) {
    if (!root.isDisposed()) {
      rows.setDimmed(dimmed);
    }
  }

  @Override
  public void showStatus(String text) {
    if (!root.isDisposed()) {
      status.setText(text);
    }
  }

  @Override
  public void showNotice(String message, String action) {
    if (root.isDisposed()) {
      return;
    }
    boolean shown = message != null;
    if (shown) {
      noticeText.setText(message.replace("&", "&&"));
      noticeButton.setVisible(action != null);
      ((GridData) noticeButton.getLayoutData()).exclude = action == null;
      if (action != null) {
        noticeButton.setText(action);
      }
    }
    ((GridData) notice.getLayoutData()).exclude = !shown;
    notice.setVisible(shown);
    root.layout(true, true);
  }

  @Override
  public void showMessage(String text) {
    if (!root.isDisposed()) {
      find.showMessage(text);
    }
  }

  @Override
  public void showFind(String text, boolean hex) {
    if (!root.isDisposed()) {
      find.show(text, hex);
    }
  }

  @Override
  public void askGoToOffset(Predicate<String> valid, Consumer<String> answer) {
    if (!root.isDisposed()) {
      host.askGoToOffset(root.getShell(), valid, answer);
    }
  }

  @Override
  public void focus() {
    if (!root.isDisposed()) {
      rows.focus();
    }
  }

  @Override
  public void setActive() {
    if (busy++ == 0 && !root.isDisposed()) {
      root.setCursor(root.getDisplay().getSystemCursor(SWT.CURSOR_WAIT));
    }
  }

  @Override
  public void setIdle() {
    if (busy > 0 && --busy == 0 && !root.isDisposed()) {
      root.setCursor(null);
    }
  }
}
