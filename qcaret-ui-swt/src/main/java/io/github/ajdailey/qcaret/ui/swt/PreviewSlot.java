package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.app.view.MarkdownViewMode;
import io.github.ajdailey.qcaret.ui.swt.markdown.MarkdownPane;
import java.util.function.Function;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * A tab's Markdown preview in its frame: the frame is a horizontal {@link SashForm} holding the
 * tab's editors and, once a mode other than Source shows, a {@link MarkdownPane} on their right.
 * Source maximizes the editors, Split shows both, Preview maximizes the pane; the focus moves with
 * the shown control. Call on the UI thread.
 */
final class PreviewSlot implements MarkdownPreviewView {
  private final SashForm frame;
  private final Composite editors;
  private final MarkdownPreviewInput input;
  private final Function<MarkdownPreviewInput, MarkdownPane> factory;
  private final Runnable focusEditor;
  private MarkdownPane pane;
  private MarkdownViewMode mode = MarkdownViewMode.SOURCE;
  private boolean weighted;

  /**
   * Creates the slot; the pane waits for the first mode other than Source.
   *
   * @param frame the tab's frame
   * @param editors the editors' control in the frame
   * @param input the tab's preview controller
   * @param factory creates the pane in the frame
   * @param focusEditor focuses the tab's focused editor
   */
  PreviewSlot(
      SashForm frame,
      Composite editors,
      MarkdownPreviewInput input,
      Function<MarkdownPreviewInput, MarkdownPane> factory,
      Runnable focusEditor) {
    this.frame = frame;
    this.editors = editors;
    this.input = input;
    this.factory = factory;
    this.focusEditor = focusEditor;
  }

  private MarkdownPane pane() {
    if (pane == null) {
      pane = factory.apply(input);
    }
    return pane;
  }

  /**
   * Returns the pane.
   *
   * @return the pane, or null before a mode other than Source showed
   */
  MarkdownPane created() {
    return pane;
  }

  /**
   * Returns the mode shown.
   *
   * @return the mode
   */
  MarkdownViewMode mode() {
    return mode;
  }

  @Override
  public void showMode(MarkdownViewMode value) {
    if (frame.isDisposed()) {
      return;
    }
    boolean editorFocused = hasFocus(editors);
    boolean paneFocused = pane != null && hasFocus(pane.control());
    mode = value;
    switch (value) {
      case SOURCE -> frame.setMaximizedControl(editors);
      case SPLIT -> {
        pane();
        frame.setMaximizedControl(null);
        if (!weighted) {
          weighted = true;
          frame.setWeights(new int[] {1, 1});
        }
      }
      case PREVIEW -> frame.setMaximizedControl(pane().control());
    }
    frame.layout(true, true);
    if (editorFocused && value == MarkdownViewMode.PREVIEW) {
      pane.focus();
    } else if (paneFocused && value != MarkdownViewMode.PREVIEW) {
      focusEditor.run();
    }
  }

  private static boolean hasFocus(Control control) {
    Control focus = control.getDisplay().getFocusControl();
    while (focus != null) {
      if (focus == control) {
        return true;
      }
      focus = focus.getParent();
    }
    return false;
  }

  @Override
  public void showRendered(MarkdownPreviewContent content) {
    pane().showRendered(content);
  }

  @Override
  public void showSizeNotice(int chars, int limit) {
    pane().showSizeNotice(chars, limit);
  }

  @Override
  public void scrollToLine(int line) {
    if (pane != null) {
      pane.scrollToLine(line);
    }
  }

  @Override
  public MarkdownImageDecoder imageDecoder() {
    return pane().imageDecoder();
  }

  @Override
  public void showImage(MarkdownImageRequest request, DecodedImage image) {
    if (pane != null) {
      pane.showImage(request, image);
    }
  }
}
