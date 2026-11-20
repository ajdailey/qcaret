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
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;

/**
 * A tab's Markdown preview in its frame: the frame is a horizontal {@link SashForm} holding the
 * tab's editors and, once a mode other than Source shows, a preview pane on their right: a {@link
 * BrowserPane} for content with HTML, else a {@link MarkdownPane}. Source maximizes the editors,
 * Split shows both, Preview maximizes the pane; the focus moves with the shown control. A Browser
 * pane that fails to create or fails a call is disposed, the content shows in the Basic pane and
 * {@link MarkdownPreviewInput#browserFailed} reports it. Call on the UI thread.
 */
final class PreviewSlot implements MarkdownPreviewView {
  private final SashForm frame;
  private final Composite editors;
  private final MarkdownPreviewInput input;
  private final Function<MarkdownPreviewInput, MarkdownPane> factory;
  private final Runnable focusEditor;
  private final Function<Runnable, BrowserPane> browserFactory;
  private MarkdownPane pane;
  private BrowserPane browser;
  private MarkdownPreviewContent content;
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
   * @param browserFactory creates a Browser pane in the frame with the failure action it is given;
   *     throws {@link SWTException} or {@link SWTError} when the browser cannot be created
   */
  PreviewSlot(
      SashForm frame,
      Composite editors,
      MarkdownPreviewInput input,
      Function<MarkdownPreviewInput, MarkdownPane> factory,
      Runnable focusEditor,
      Function<Runnable, BrowserPane> browserFactory) {
    this.frame = frame;
    this.editors = editors;
    this.input = input;
    this.factory = factory;
    this.focusEditor = focusEditor;
    this.browserFactory = browserFactory;
  }

  private MarkdownPane pane() {
    if (pane == null) {
      pane = factory.apply(input);
      if (browser != null) {
        pane.control().setVisible(false);
      }
    }
    return pane;
  }

  /** The control the preview shows: the Browser pane while one shows, else the Basic pane. */
  private Control shown() {
    return browser != null ? browser.control() : pane().control();
  }

  /**
   * Returns the Browser pane.
   *
   * @return the pane, or null while the Basic pane shows
   */
  BrowserPane browserPane() {
    return browser;
  }

  /** Focuses the shown preview pane. */
  void focus() {
    if (browser != null) {
      browser.focus();
    } else if (pane != null) {
      pane.focus();
    }
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
    boolean paneFocused =
        (pane != null && hasFocus(pane.control()))
            || (browser != null && hasFocus(browser.control()));
    mode = value;
    switch (value) {
      case SOURCE -> frame.setMaximizedControl(editors);
      case SPLIT -> {
        shown();
        frame.setMaximizedControl(null);
        if (!weighted) {
          weighted = true;
          frame.setWeights(evenWeights());
        }
      }
      case PREVIEW -> frame.setMaximizedControl(shown());
    }
    frame.layout(true, true);
    if (editorFocused && value == MarkdownViewMode.PREVIEW) {
      focus();
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
  public void showRendered(MarkdownPreviewContent value) {
    if (frame.isDisposed()) {
      return;
    }
    content = value;
    if (value.html() != null && browser == null) {
      openBrowser();
    } else if (value.html() == null && browser != null) {
      closeBrowser();
    }
    if (browser != null) {
      browser.showRendered(value);
    } else {
      pane().showRendered(value);
    }
  }

  private void openBrowser() {
    int[] weights = paneWeights();
    try {
      browser =
          browserFactory.apply(
              new Runnable() {
                @Override
                public void run() {
                  browserFailed();
                }
              });
    } catch (SWTException | SWTError e) {
      BrowserPreview.failed(frame.getDisplay(), e.getMessage());
      input.browserFailed();
      return;
    }
    if (pane != null) {
      pane.control().setVisible(false);
    }
    relayout(weights);
  }

  private void closeBrowser() {
    int[] weights = paneWeights();
    BrowserPane closing = browser;
    browser = null;
    pane().control().setVisible(true);
    closing.control().setVisible(false);
    relayout(weights);
    closing.dispose();
  }

  /** A Browser call failed: the Basic pane shows the content and the controller renders again. */
  private void browserFailed() {
    if (browser == null || frame.isDisposed()) {
      return;
    }
    closeBrowser();
    if (content != null) {
      pane().showRendered(content);
    }
    input.browserFailed();
  }

  /**
   * Returns the editors' weight and the shown pane's, before a swap. {@link SashForm#getWeights}
   * holds one weight per child, hidden ones included, in child order with the editors first.
   */
  private int[] paneWeights() {
    int[] all = frame.getWeights();
    Control[] children = frame.getChildren();
    int shown = 1;
    int at = 0;
    for (Control child : children) {
      if (child instanceof org.eclipse.swt.widgets.Sash) {
        continue;
      }
      if (at > 0 && child.getVisible() && at < all.length) {
        shown = all[at];
      }
      at++;
    }
    return new int[] {all.length > 0 ? all[0] : 1, shown};
  }

  /** One weight per child, each the same. */
  private int[] evenWeights() {
    int[] all = frame.getWeights();
    java.util.Arrays.fill(all, 1);
    return all;
  }

  /** Puts the shown pane in the frame for the current mode with the sash where it was. */
  private void relayout(int[] weights) {
    if (mode == MarkdownViewMode.PREVIEW) {
      frame.setMaximizedControl(shown());
    } else if (mode == MarkdownViewMode.SPLIT) {
      int[] all = frame.getWeights();
      java.util.Arrays.fill(all, weights[1]);
      all[0] = weights[0];
      frame.setWeights(all);
    }
    frame.layout(true, true);
  }

  @Override
  public void showSizeNotice(int chars, int limit) {
    if (browser != null) {
      browser.showSizeNotice(chars, limit);
    } else {
      pane().showSizeNotice(chars, limit);
    }
  }

  @Override
  public void scrollToLine(int line) {
    if (browser != null) {
      browser.scrollToLine(line);
    } else if (pane != null) {
      pane.scrollToLine(line);
    }
  }

  @Override
  public void selectRendered(int start, int end) {
    if (browser == null && pane != null) {
      pane.selectRendered(start, end);
    }
  }

  @Override
  public void scrollToAnchor(String id) {
    if (browser != null) {
      browser.scrollToAnchor(id);
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
