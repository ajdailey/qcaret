package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ActivityIndicator;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.HexScroll;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.hex.HexBlocks;
import io.github.ajdailey.qcaret.core.hex.HexRow;
import io.github.ajdailey.qcaret.core.hex.HexRows;
import io.github.ajdailey.qcaret.core.hex.HexSource;
import io.github.ajdailey.qcaret.core.hex.HexViewport;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One hex view's controller: a tab's pane or a Hex Viewer window. It owns the source's block cache,
 * the layout, the viewport and the caret ({@link HexCursor}); Copy, Go to Offset and Find act on
 * them ({@link HexCopier}, {@link HexFind}). Confined to the UI thread.
 *
 * <ul>
 *   <li>A paint asks for rows through {@link #row}. A missing row paints blank and asks {@link
 *       HexReads} for the visible rows; the read's end redraws the view.
 *   <li>Every read, find and copy carries the generation of the source it started on, and {@link
 *       #setSource} moves the generation on, so a result for a replaced source is dropped.
 *   <li>A file that changed on disk fails the read; the {@link Host} shows the notice. Another read
 *       failure shows a message, and no read starts again until the source is replaced.
 * </ul>
 */
final class HexController implements HexInput {
  /** The owner: a tab's hex pane or a Hex Viewer window. */
  interface Host {
    /** Returns the clipboard. */
    ClipboardService clipboard();

    /** The user moved the caret or the selection. */
    void selectionChanged(HexSelection selection);

    /** The rows gained or lost the keyboard focus. */
    void focusChanged(boolean focused);

    /** A read found the file changed on disk. */
    void sourceChanged();

    /** The notice bar's button was pressed. */
    void noticeAction();

    /** The view was disposed: the window closed or the tab's pane went. */
    void viewClosed();
  }

  private final Host host;
  private final HexCursor cursor;
  private final HexViewport viewport;
  private final HexFind find;
  private final HexCopier copier;
  private final HexReads reads;
  private final HexTarget target = new HexTarget();
  private HexView view;
  private HexSource source;
  private HexBlocks blocks;
  private HexRows rows;
  private long generation;
  private boolean dimmed;
  private String notice;
  private String noticeAction;

  /**
   * Creates the controller with no source.
   *
   * @param workers runs reads, finds and copies
   * @param ui receives their results
   * @param host the owner
   * @param fileReads true when sources read a file: reads run on {@code qcaret-io-} threads
   * @param bytesPerRow one of {@link HexRows#WIDTHS}
   */
  HexController(Workers workers, UiDispatcher ui, Host host, boolean fileReads, int bytesPerRow) {
    this.host = host;
    this.cursor = new HexCursor(bytesPerRow);
    this.viewport = new HexViewport(bytesPerRow);
    this.find = new HexFind(workers, ui);
    this.copier = new HexCopier(workers, ui);
    this.reads =
        new HexReads(
            workers,
            ui,
            fileReads,
            new HexReads.Listener() {
              @Override
              public void loaded() {
                if (view != null) {
                  view.redraw();
                  request();
                }
              }

              @Override
              public void changed() {
                host.sourceChanged();
              }

              @Override
              public void failed(IOException error) {
                message(UiText.HEX_READ_FAILED.format(LogText.oneLine(error.getMessage())));
              }
            });
    this.rows = new HexRows(0, bytesPerRow);
  }

  /**
   * Shows other bytes, keeping the caret, the selection and the top row where the new size allows.
   * Running reads, finds and copies of the old source are dropped.
   *
   * @param next the bytes
   */
  void setSource(HexSource next) {
    generation++;
    find.cancel();
    source = next;
    blocks = new HexBlocks(next);
    reads.reset(blocks);
    rows = new HexRows(next.size(), viewport.bytesPerRow());
    viewport.setSize(next.size());
    cursor.setSize(next.size());
    if (view != null) {
      view.showLayout(rows);
      showScroll();
      view.showSelection(cursor.selection());
      showStatus();
      view.redraw();
    }
  }

  /** Returns the bytes shown, or null before {@link #setSource}. */
  HexSource source() {
    return source;
  }

  /** Returns the generation, which {@link #setSource} moves on. */
  long generation() {
    return generation;
  }

  /** Sets the bytes per row; the top row keeps its first byte. */
  void setBytesPerRow(int bytesPerRow) {
    if (bytesPerRow == viewport.bytesPerRow()) {
      return;
    }
    viewport.setBytesPerRow(bytesPerRow);
    cursor.setBytesPerRow(bytesPerRow);
    rows = new HexRows(rows.size(), bytesPerRow);
    if (view != null) {
      view.showLayout(rows);
      showScroll();
      view.redraw();
    }
  }

  /** Returns the bytes per row. */
  int bytesPerRow() {
    return viewport.bytesPerRow();
  }

  /** Dims the rows while the source is being replaced. */
  void setDimmed(boolean value) {
    dimmed = value;
    if (view != null) {
      view.setDimmed(value);
    }
  }

  /** Shows a notice bar, or hides it with a null message. */
  void notice(String message, String action) {
    notice = message;
    noticeAction = action;
    if (view != null) {
      view.showNotice(message, action);
    }
  }

  /** Returns the caret and selection. */
  HexSelection selection() {
    return cursor.selection();
  }

  /**
   * Selects a range and scrolls its caret end into view, without reporting to the host.
   *
   * @param anchor where the selection starts
   * @param caret the caret
   */
  void select(long anchor, long caret) {
    cursor.select(anchor, caret);
    showSelection(false);
  }

  /** Returns the view, or null while none is attached. */
  HexView view() {
    return view;
  }

  /** Stops running work; the controller is not used again. */
  void dispose() {
    generation++;
    find.cancel();
    reads.cancel();
    view = null;
  }

  @Override
  public void attach(HexView value) {
    boolean closed = view != null && value == null;
    view = value;
    if (view == null) {
      if (closed) {
        host.viewClosed();
      }
      return;
    }
    view.showLayout(rows);
    showScroll();
    view.showSelection(cursor.selection());
    view.setDimmed(dimmed);
    view.showNotice(notice, noticeAction);
    showStatus();
  }

  @Override
  public void detach(HexView value) {
    if (value != null && value == view) {
      attach(null);
    }
  }

  @Override
  public HexRow row(long row) {
    if (blocks == null || row < 0 || row >= rows.rowCount()) {
      return null;
    }
    HexRow built = rows.row(row, blocks);
    if (built == null) {
      request();
    }
    return built;
  }

  @Override
  public void resized(int count) {
    viewport.resize(count);
    showScroll();
  }

  @Override
  public void scrolled(int selection) {
    viewport.setScrollbarSelection(selection);
    showScroll();
  }

  @Override
  public void scrollBy(long count) {
    viewport.scrollBy(count);
    showScroll();
  }

  @Override
  public void key(HexKey key, boolean extend) {
    cursor.move(key, extend, viewport.rows());
    showSelection(true);
  }

  @Override
  public void switchColumn() {
    cursor.switchColumn();
    if (view != null) {
      view.showSelection(cursor.selection());
    }
  }

  @Override
  public void clicked(long offset, boolean textColumn, boolean extend) {
    cursor.click(offset, textColumn, extend);
    showSelection(true);
  }

  @Override
  public void dragged(long offset) {
    cursor.drag(offset);
    showSelection(true);
  }

  @Override
  public void selectAll() {
    cursor.selectAll();
    showSelection(true);
  }

  @Override
  public void focusChanged(boolean focused) {
    host.focusChanged(focused);
  }

  @Override
  public void copy(HexCopy kind) {
    HexSelection selection = cursor.selection();
    switch (kind) {
      case OFFSET -> host.clipboard().setText(rows.offsetText(selection.caret()));
      case HEX -> copier.copy(target, selection, true);
      case TEXT -> copier.copy(target, selection, false);
      case FOCUSED -> copier.copy(target, selection, !selection.textColumn());
    }
  }

  @Override
  public void goToOffset() {
    if (view == null || source == null) {
      return;
    }
    final long size = source.size();
    view.askGoToOffset(
        new Predicate<String>() {
          @Override
          public boolean test(String text) {
            return HexOffsetText.parse(text, cursor.selection().caret(), size) >= 0;
          }
        },
        new Consumer<String>() {
          @Override
          public void accept(String text) {
            long offset = HexOffsetText.parse(text, cursor.selection().caret(), rows.size());
            if (offset >= 0) {
              cursor.select(offset, offset);
              showSelection(true);
            }
          }
        });
  }

  @Override
  public void openFind() {
    if (view != null) {
      view.showFind(find.text(), find.hex());
    }
  }

  /**
   * Finds again with the last pattern; opens the find bar when there is none.
   *
   * @param forward true for Next
   */
  void findAgain(boolean forward) {
    if (find.text().isEmpty()) {
      openFind();
    } else {
      find(find.text(), find.hex(), forward);
    }
  }

  @Override
  public void find(String text, boolean hex, boolean forward) {
    find.find(target, text, hex, forward, cursor.selection());
  }

  @Override
  public void cancel() {
    if (find.cancel()) {
      message("");
    }
  }

  @Override
  public void noticeAction() {
    host.noticeAction();
  }

  private void message(String text) {
    if (view != null) {
      view.showMessage(text);
    }
  }

  /** Shows the caret; with {@code report}, tells the host it moved. */
  private void showSelection(boolean report) {
    HexSelection selection = cursor.selection();
    viewport.ensureOffsetVisible(Math.min(selection.caret(), Math.max(0, rows.size() - 1)));
    if (view != null) {
      view.showSelection(selection);
      showScroll();
      showStatus();
    }
    if (report) {
      host.selectionChanged(selection);
    }
  }

  private void showScroll() {
    if (view != null) {
      view.showScroll(
          new HexScroll(
              viewport.topRow(),
              viewport.scrollbarMaximum(),
              viewport.scrollbarThumb(),
              viewport.scrollbarSelection()));
    }
  }

  private void showStatus() {
    if (view != null) {
      view.showStatus(HexOffsetText.status(rows, cursor.selection()));
    }
  }

  /** Reads the visible rows unless they are cached. */
  private void request() {
    if (source == null) {
      return;
    }
    long start = viewport.topOffset();
    long end = Math.min(source.size(), start + (long) viewport.rows() * viewport.bytesPerRow());
    reads.request(start, (int) Math.max(0, end - start));
  }

  /** The view as {@link HexFind} and {@link HexCopier} use it. */
  private final class HexTarget implements HexFind.Target, HexCopier.Target {
    @Override
    public HexSource source() {
      return source;
    }

    @Override
    public long generation() {
      return generation;
    }

    @Override
    public ActivityIndicator busy() {
      final HexView shown = view;
      return new ActivityIndicator() {
        @Override
        public void setActive() {
          if (shown != null) {
            shown.setActive();
          }
        }

        @Override
        public void setIdle() {
          if (shown != null) {
            shown.setIdle();
          }
        }
      };
    }

    @Override
    public void found(long start, long length) {
      cursor.select(start, start + length);
      showSelection(true);
    }

    @Override
    public void message(String text) {
      HexController.this.message(text);
    }

    @Override
    public ClipboardService clipboard() {
      return host.clipboard();
    }
  }
}
