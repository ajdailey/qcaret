package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.theme.Rgb;
import io.github.ajdailey.qcaret.app.view.Comparison;
import io.github.ajdailey.qcaret.ui.swt.editor.EditorPane;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.LineBackgroundEvent;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * A read-only side-by-side view of a {@link Comparison}: two {@code StyledText} controls in a
 * {@link SashForm}, each row's background showing its kind, the top row and horizontal offset kept
 * equal on both sides, the side that moved leading. It opens at the first difference; Next and
 * Previous Difference (F7 and Shift+F7) scroll both sides to a run of changed rows. Modeless and
 * owned by the main window; {@link #setPalette} recolors it.
 */
final class DiffWindow {
  /** Rows shown above a difference scrolled to. */
  static final int CONTEXT_ROWS = 3;

  private final Shell shell;
  private final Comparison comparison;
  private final StyledText left;
  private final StyledText right;
  private final Label position;
  private Font font;
  private Palette palette;
  private int current = -1;
  private int syncedTop;
  private int syncedPixel;

  DiffWindow(Shell parent, Comparison comparison, Palette palette, FontData[] fontData) {
    this.comparison = comparison;
    this.palette = palette;
    shell = new Shell(parent, SWT.SHELL_TRIM);
    shell.setText(
        UiText.COMPARE_WINDOW_TITLE.format(comparison.leftTitle(), comparison.rightTitle()));
    GridLayout layout = new GridLayout(4, false);
    layout.marginWidth = 6;
    layout.marginHeight = 6;
    shell.setLayout(layout);
    font = new Font(shell.getDisplay(), fontData);
    Label notice = new Label(shell, SWT.NONE);
    notice.setText(comparison.notice());
    notice.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    position = new Label(shell, SWT.NONE);
    position.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
    Button previous =
        Dialogs.button(
            shell,
            UiText.COMPARE_PREVIOUS.text(),
            new Runnable() {
              @Override
              public void run() {
                previous();
              }
            });
    Button next =
        Dialogs.button(
            shell,
            UiText.COMPARE_NEXT.text(),
            new Runnable() {
              @Override
              public void run() {
                next();
              }
            });
    boolean any = !comparison.changes().isEmpty();
    previous.setEnabled(any);
    next.setEnabled(any);
    SashForm sides = new SashForm(shell, SWT.HORIZONTAL);
    sides.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 4, 1));
    left = side(sides, comparison.leftTitle(), comparison.leftText(), true);
    right = side(sides, comparison.rightTitle(), comparison.rightText(), false);
    follow(left, right);
    follow(right, left);
    shell.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            font.dispose();
          }
        });
    setPalette(palette);
    showPosition();
  }

  /**
   * Shows both sides in another font.
   *
   * @param fontData the font
   */
  void setFontData(FontData[] fontData) {
    Font old = font;
    font = new Font(shell.getDisplay(), fontData);
    left.setFont(font);
    right.setFont(font);
    old.dispose();
  }

  private StyledText side(Composite parent, String title, String text, final boolean isLeft) {
    Composite box = new Composite(parent, SWT.NONE);
    GridLayout layout = new GridLayout(1, false);
    layout.marginWidth = 0;
    layout.marginHeight = 0;
    box.setLayout(layout);
    Label label = new Label(box, SWT.NONE);
    label.setText(title.replace("&", "&&"));
    label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    final StyledText view =
        new StyledText(box, SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
    view.setData(EditorPane.PALETTE_COLORED, Boolean.TRUE);
    view.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    view.setFont(font);
    view.setText(text);
    view.addLineBackgroundListener(
        new LineBackgroundListener() {
          @Override
          public void lineGetBackground(LineBackgroundEvent event) {
            int row = view.getLineAtOffset(event.lineOffset);
            if (row < comparison.rows().size()) {
              event.lineBackground = background(comparison.rows().get(row), isLeft);
            }
          }
        });
    view.addListener(
        SWT.KeyDown,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.keyCode == SWT.F7) {
              if ((event.stateMask & SWT.SHIFT) != 0) {
                previous();
              } else {
                next();
              }
              event.doit = false;
            } else if (event.keyCode == SWT.ESC) {
              shell.dispose();
            }
          }
        });
    return view;
  }

  /**
   * Copies {@code view}'s top row and horizontal offset to {@code other} after a scroll bar move, a
   * caret move or a paint, when they differ from the last position either side reached: the side
   * that moved leads, and a stale paint of the other side does not pull it back.
   */
  private void follow(final StyledText view, final StyledText other) {
    Listener sync =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            sync(view, other);
          }
        };
    view.addListener(SWT.Paint, sync);
    view.getVerticalBar().addListener(SWT.Selection, sync);
    view.getHorizontalBar().addListener(SWT.Selection, sync);
    view.addCaretListener(
        new CaretListener() {
          @Override
          public void caretMoved(CaretEvent event) {
            sync(view, other);
          }
        });
  }

  private void sync(StyledText view, StyledText other) {
    if (view.isDisposed() || other.isDisposed()) {
      return;
    }
    if (view.getTopIndex() != syncedTop) {
      syncedTop = view.getTopIndex();
      other.setTopIndex(syncedTop);
    }
    if (view.getHorizontalPixel() != syncedPixel) {
      syncedPixel = view.getHorizontalPixel();
      other.setHorizontalPixel(syncedPixel);
    }
  }

  private Color background(Comparison.Row row, boolean isLeft) {
    Rgb rgb =
        switch (row) {
          case SAME -> null;
          case CHANGED -> palette.diffChanged();
          case REMOVED -> isLeft ? palette.diffRemoved() : palette.gutter();
          case ADDED -> isLeft ? palette.gutter() : palette.diffAdded();
        };
    return rgb == null ? null : new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /**
   * Recolors both sides.
   *
   * @param next the palette
   */
  void setPalette(Palette next) {
    palette = next;
    Color text = color(next.text());
    Color background = color(next.background());
    for (StyledText view : new StyledText[] {left, right}) {
      if (!view.isDisposed()) {
        view.setForeground(text);
        view.setBackground(background);
        view.redraw();
      }
    }
  }

  private static Color color(Rgb rgb) {
    return new Color(rgb.red(), rgb.green(), rgb.blue());
  }

  /** Scrolls to the next run of changed rows, wrapping to the first. */
  void next() {
    int count = comparison.changes().size();
    if (count > 0) {
      show((current + 1) % count);
    }
  }

  /** Scrolls to the previous run of changed rows, wrapping to the last. */
  void previous() {
    int count = comparison.changes().size();
    if (count > 0) {
      show(current <= 0 ? count - 1 : current - 1);
    }
  }

  private void show(int index) {
    current = index;
    int row = comparison.changes().get(index);
    syncedTop = Math.max(0, row - CONTEXT_ROWS);
    for (StyledText view : new StyledText[] {left, right}) {
      view.setTopIndex(syncedTop);
      view.setCaretOffset(view.getOffsetAtLine(row));
    }
    showPosition();
  }

  private void showPosition() {
    int count = comparison.changes().size();
    position.setText(
        count == 0
            ? UiText.COMPARE_NONE.text()
            : UiText.COMPARE_POSITION.format(Math.max(current, 0) + 1, count));
    position.getParent().layout(new Control[] {position});
  }

  void open() {
    shell.setSize(1000, 700);
    shell.open();
    next();
  }

  Shell shell() {
    return shell;
  }

  StyledText left() {
    return left;
  }

  StyledText right() {
    return right;
  }

  /** Returns the difference shown, or -1 when there is none. */
  int current() {
    return current;
  }
}
