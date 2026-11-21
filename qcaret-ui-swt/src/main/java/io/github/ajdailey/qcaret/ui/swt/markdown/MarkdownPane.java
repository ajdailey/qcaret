package io.github.ajdailey.qcaret.ui.swt.markdown;

import io.github.ajdailey.qcaret.app.editor.MarkdownSpans;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.markdown.MarkdownTable;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.ui.swt.platform.AccessibleName;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineBackgroundEvent;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.LineStyleEvent;
import org.eclipse.swt.custom.LineStyleListener;
import org.eclipse.swt.custom.PaintObjectEvent;
import org.eclipse.swt.custom.PaintObjectListener;
import org.eclipse.swt.custom.ST;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * The Basic Markdown preview of one tab: a read-only, wrapping {@link StyledText} holding the
 * render's text, with the size notice above it while the document is too large to render on its
 * own.
 *
 * <ul>
 *   <li>Styles: {@link MarkdownStyler} styles each line as it paints, {@link MarkdownPainter}
 *       paints quote bars, rules, table borders and images. A table row's spaces show as no-break
 *       spaces so a cell does not wrap; Copy puts the render's own text on the clipboard.
 *   <li>Tables: {@link TableColumns} measures columns in bounded UI turns.
 *   <li>Images: a {@link SwtImageDecoder} decodes on the controller's worker; {@link PaneImages}
 *       creates and disposes the {@code Image}s, all of them on new content and on dispose.
 *   <li>Input: a click goes to {@link MarkdownPreviewInput#clicked} with its offset; a paint that
 *       finds another top line reports it through {@link MarkdownPreviewInput#previewScrolled},
 *       except after {@link #scrollToLine}.
 *   <li>Theme: {@link #setPalette} recolors in place.
 * </ul>
 *
 * Call on the UI thread.
 */
public final class MarkdownPane {
  private final Composite root;
  private final Composite notice;
  private final Label noticeText;
  private final StyledText text;
  private final MarkdownPreviewInput input;
  private final MarkdownFonts fonts;
  private final PaneImages images;
  private final TableColumns tables;
  private final MarkdownStyler styler;
  private final MarkdownPainter painter;
  private MarkdownPreviewContent content;
  private String shownText = "";
  private SwtImageDecoder decoder;
  private Clipboard clipboard;
  private int reportedTop;

  /**
   * Creates the pane, empty.
   *
   * @param parent the tab's frame
   * @param input receives clicks, scrolls and the render request
   * @param palette the palette it starts with
   * @param ui runs table measuring in later UI turns
   * @param monospace the code font's family
   */
  public MarkdownPane(
      Composite parent,
      final MarkdownPreviewInput input,
      Palette palette,
      UiDispatcher ui,
      String monospace) {
    this.input = input;
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
    Button render = new Button(notice, SWT.PUSH);
    render.setText(UiText.MARKDOWN_RENDER.text());
    render.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            input.renderRequested();
          }
        });
    text = new StyledText(root, SWT.READ_ONLY | SWT.WRAP | SWT.MULTI | SWT.V_SCROLL);
    text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    // No top margin: a line scrolled to the top is fully visible, so getTopIndex returns it.
    text.setMargins(12, 0, 12, 8);
    AccessibleName.attachKeepingRole(text, UiText.ACCESSIBLE_MARKDOWN_PREVIEW.text());
    fonts = new MarkdownFonts(text.getDisplay(), text.getFont().getFontData()[0], monospace);
    images = new PaneImages(text.getDisplay());
    tables = new TableColumns(text, fonts, ui);
    styler = new MarkdownStyler(fonts, images, tables, palette);
    painter = new MarkdownPainter(text, styler, tables, images);
    listen();
    menu();
    setPalette(palette);
  }

  private void listen() {
    text.addLineStyleListener(
        new LineStyleListener() {
          @Override
          public void lineGetStyle(LineStyleEvent event) {
            int line = text.getLineAtOffset(event.lineOffset);
            styler
                .style(line, event.lineOffset, event.lineOffset + event.lineText.length())
                .applyTo(event);
          }
        });
    text.addLineBackgroundListener(
        new LineBackgroundListener() {
          @Override
          public void lineGetBackground(LineBackgroundEvent event) {
            event.lineBackground = styler.background(text.getLineAtOffset(event.lineOffset));
          }
        });
    text.addPaintObjectListener(
        new PaintObjectListener() {
          @Override
          public void paintObject(PaintObjectEvent event) {
            painter.paintObject(event);
          }
        });
    text.addListener(
        SWT.Paint,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            painter.paint(event.gc);
            int top = text.getTopIndex();
            if (top != reportedTop) {
              reportedTop = top;
              input.previewScrolled(top);
            }
          }
        });
    text.addListener(
        SWT.Resize,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            styler.setPaneWidth(textWidth());
            if (images.offsets().length > 0) {
              restyle();
            }
          }
        });
    text.addListener(
        SWT.MouseUp,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            if (event.button == 1 && text.getSelectionCount() == 0) {
              int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
              if (offset >= 0) {
                input.clicked(offset);
              }
            }
          }
        });
    text.addListener(
        SWT.MouseMove,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            int offset = text.getOffsetAtPoint(new Point(event.x, event.y));
            text.setCursor(
                link(offset) ? text.getDisplay().getSystemCursor(SWT.CURSOR_HAND) : null);
          }
        });
    text.addVerifyKeyListener(
        new VerifyKeyListener() {
          @Override
          public void verifyKey(VerifyEvent event) {
            if (text.getKeyBinding(event.keyCode | event.stateMask) == ST.COPY) {
              event.doit = false;
              copy();
            }
          }
        });
    text.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            tables.cancel();
            images.dispose();
            fonts.dispose();
            if (clipboard != null) {
              clipboard.dispose();
            }
          }
        });
  }

  private void menu() {
    Menu menu = new Menu(text);
    MenuItem copy = new MenuItem(menu, SWT.PUSH);
    copy.setText(UiText.COMMAND_COPY.text());
    copy.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            copy();
          }
        });
    MenuItem all = new MenuItem(menu, SWT.PUSH);
    all.setText(UiText.COMMAND_SELECT_ALL.text());
    all.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            text.selectAll();
          }
        });
    text.setMenu(menu);
  }

  private boolean link(int offset) {
    if (offset < 0 || content == null) {
      return false;
    }
    int[] spans = content.spans();
    int i = MarkdownSpans.first(spans, offset);
    return 3 * i < spans.length
        && spans[3 * i] <= offset
        && (spans[3 * i + 2] & MarkdownSpans.LINK) != 0;
  }

  /** Puts the selected part of the render's text on the clipboard. */
  void copy() {
    Point selection = text.getSelectionRange();
    if (content == null || selection.y == 0) {
      return;
    }
    if (clipboard == null) {
      clipboard = new Clipboard(text.getDisplay());
    }
    String copied = content.rendered().text().substring(selection.x, selection.x + selection.y);
    clipboard.setContents(new Object[] {copied}, new Transfer[] {TextTransfer.getInstance()});
  }

  /**
   * Returns the pane's control.
   *
   * @return the composite holding the notice and the text
   */
  public Control control() {
    return root;
  }

  /**
   * Returns the text widget.
   *
   * @return the widget
   */
  public StyledText text() {
    return text;
  }

  /** Focuses the text. */
  public void focus() {
    if (!text.isDisposed()) {
      text.setFocus();
    }
  }

  /**
   * Shows new content, keeping the top line where the new text has it; hides the size notice.
   * {@link TextDelta} changes the text; the visible lines are styled again.
   *
   * @param next the render and its styles
   */
  public void showRendered(MarkdownPreviewContent next) {
    if (text.isDisposed()) {
      return;
    }
    setNotice(null);
    int top = text.getTopIndex();
    content = next;
    images.reset(next.images());
    styler.setContent(next);
    painter.setRendered(next.rendered());
    String nextText = displayText(next.rendered());
    TextDelta.apply(text, shownText, nextText);
    shownText = nextText;
    text.setTopIndex(Math.min(top, text.getLineCount() - 1));
    reportedTop = text.getTopIndex();
    int bottom = bottomLine();
    int start = text.getOffsetAtLine(reportedTop);
    int end =
        bottom + 1 < text.getLineCount() ? text.getOffsetAtLine(bottom + 1) : nextText.length();
    if (end > start) {
      text.redrawRange(start, end - start, true);
    }
    tables.reset(next.rendered(), reportedTop, bottom);
  }

  /**
   * Shows the size notice with its render action.
   *
   * @param chars the document's length
   * @param limit the longest document rendered on its own
   */
  public void showSizeNotice(int chars, int limit) {
    if (!text.isDisposed()) {
      setNotice(UiText.MARKDOWN_SIZE_NOTICE.format(chars, limit));
    }
  }

  private void setNotice(String message) {
    boolean shown = message != null;
    if (shown) {
      noticeText.setText(message);
    }
    if (notice.getVisible() != shown) {
      notice.setVisible(shown);
      ((GridData) notice.getLayoutData()).exclude = !shown;
      root.layout(true, true);
    }
  }

  /**
   * Reports whether the size notice shows.
   *
   * @return true while it shows
   */
  public boolean noticeShown() {
    return notice.getVisible();
  }

  /**
   * Scrolls so a line is at the top; the scroll is not reported back.
   *
   * @param line a rendered line
   */
  public void scrollToLine(int line) {
    if (!text.isDisposed() && text.getLineCount() > 0) {
      text.setTopIndex(Math.max(0, Math.min(line, text.getLineCount() - 1)));
      reportedTop = text.getTopIndex();
    }
  }

  /**
   * Selects text and scrolls it into view; the scroll is not reported back.
   *
   * @param start the first offset
   * @param end the offset after the text
   */
  public void selectRendered(int start, int end) {
    int length = text.isDisposed() ? -1 : text.getCharCount();
    if (start >= 0 && start <= end && end <= length) {
      text.setSelection(start, end);
      reportedTop = text.getTopIndex();
    }
  }

  /**
   * Returns a decoder for the pane's current width.
   *
   * @return the decoder
   */
  public MarkdownImageDecoder imageDecoder() {
    decoder = new SwtImageDecoder(textWidth());
    return decoder;
  }

  /**
   * Draws a decoded image; a result for earlier content is dropped.
   *
   * @param request the image's request
   * @param image the image, or null for a placeholder
   */
  public void showImage(MarkdownImageRequest request, DecodedImage image) {
    if (!text.isDisposed() && images.accept(request, image)) {
      TableColumns.restyle(text, request.offset(), request.offset() + 1);
    }
  }

  /**
   * Recolors the pane.
   *
   * @param palette the palette
   */
  public void setPalette(Palette palette) {
    styler.setPalette(palette);
    text.setBackground(MarkdownStyler.color(palette.background()));
    text.setForeground(MarkdownStyler.color(palette.text()));
    text.setSelectionBackground(MarkdownStyler.color(palette.selection()));
    text.setSelectionForeground(MarkdownStyler.color(palette.selectionText()));
    restyle();
  }

  /** Styles every line again on its next paint. */
  private void restyle() {
    if (text.getCharCount() > 0) {
      text.redrawRange(0, text.getCharCount(), true);
    }
  }

  private int textWidth() {
    return Math.max(1, text.getClientArea().width - text.getLeftMargin() - text.getRightMargin());
  }

  private int bottomLine() {
    return text.getLineIndex(Math.max(0, text.getClientArea().height - 1));
  }

  /** The render's text with each table row's spaces as no-break spaces. */
  private static String displayText(RenderedMarkdown rendered) {
    String plain = rendered.text();
    if (rendered.tables().isEmpty()) {
      return plain;
    }
    char[] chars = plain.toCharArray();
    for (MarkdownTable table : rendered.tables()) {
      int start = rendered.lineStart(table.firstLine());
      int last = table.firstLine() + table.rowCount() - 1;
      int end = last + 1 < rendered.lineCount() ? rendered.lineStart(last + 1) : chars.length;
      for (int i = start; i < end; i++) {
        if (chars[i] == ' ') {
          chars[i] = ' ';
        }
      }
    }
    return new String(chars);
  }

  // For tests.

  /** Returns the styler. */
  MarkdownStyler styler() {
    return styler;
  }

  /** Returns the table measuring. */
  TableColumns tables() {
    return tables;
  }

  /** Returns the images. */
  PaneImages images() {
    return images;
  }

  /** Returns the last decoder handed out, or null. */
  SwtImageDecoder decoder() {
    return decoder;
  }
}
