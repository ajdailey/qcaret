package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Browser Markdown preview of one tab: an SWT {@link Browser} showing a render's HTML through
 * {@code setText(page, false)}, with the size notice above it.
 *
 * <ul>
 *   <li>JavaScript is off, and the page's content security policy ({@link MarkdownPage#POLICY})
 *       loads nothing but inline styles and {@code data:} images.
 *   <li>Every navigation is canceled except the page this pane loads and the {@code about:blank#id}
 *       scroll it asks for. A canceled navigation within {@value #GESTURE_MILLIS} ms of a
 *       primary-button release or an Enter or Space key on the browser goes to {@link
 *       MarkdownPreviewInput#linkFollowed}; any other one, such as a meta refresh, is dropped.
 *   <li>Scrolling to a line or an anchor navigates to {@code about:blank#id} where {@link
 *       PlatformTraits#scrollsBrowserToFragments} holds, after the page loaded; a new page returns
 *       to the last id asked for. The browser's own scroll is not reported: with JavaScript off the
 *       page position cannot be read.
 *   <li>An {@link SWTException} or {@link SWTError} from a browser call runs the failure action
 *       once; no browser exception leaves this class.
 * </ul>
 *
 * Call on the UI thread.
 */
final class BrowserPane {
  private static final Logger LOG = LoggerFactory.getLogger(BrowserPane.class);

  /** Time from a click or key on the browser to the navigation it starts. */
  static final int GESTURE_MILLIS = 1000;

  private static final String BLANK = "about:blank";

  private final Composite root;
  private final Composite notice;
  private final Label noticeText;
  private final Browser browser;
  private final MarkdownPreviewInput input;
  private final Runnable failure;
  private final UiDispatcher ui;
  private final Runnable gestureEnd =
      new Runnable() {
        @Override
        public void run() {
          gesture = false;
        }
      };
  private Palette palette;
  private MarkdownPreviewContent content;
  private String expected;
  private boolean loaded;
  private boolean gesture;
  private boolean failed;
  private String anchor;

  /**
   * Creates the pane, empty.
   *
   * @param parent the tab's frame
   * @param input receives links and the render request
   * @param palette the palette the page starts with
   * @param factory creates the browser control
   * @param ui runs the gesture's time limit
   * @param failure runs once when a browser call fails, after this pane recorded it
   * @throws SWTException when the browser control cannot be created
   * @throws SWTError when the browser control cannot be created
   */
  BrowserPane(
      Composite parent,
      final MarkdownPreviewInput input,
      Palette palette,
      GuideWindow.BrowserFactory factory,
      UiDispatcher ui,
      Runnable failure) {
    this.input = input;
    this.ui = ui;
    this.palette = palette;
    this.failure = failure;
    root = new Composite(parent, SWT.NONE);
    try {
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
      browser = factory.create(root);
      browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
      browser.setJavascriptEnabled(false);
      listen();
    } catch (SWTException | SWTError e) {
      root.dispose();
      throw e;
    }
  }

  private void listen() {
    browser.addLocationListener(
        new LocationAdapter() {
          @Override
          public void changing(LocationEvent event) {
            if (expected != null && same(event.location, expected)) {
              expected = null;
              return;
            }
            event.doit = false;
            if (gesture) {
              gesture = false;
              input.linkFollowed(event.location);
            } else {
              LOG.debug("Markdown preview dropped {}", LogText.oneLine(event.location));
            }
          }
        });
    browser.addProgressListener(
        new ProgressAdapter() {
          @Override
          public void completed(ProgressEvent event) {
            loaded = true;
            if (anchor != null) {
              scroll(anchor);
            }
          }
        });
    Listener gestures =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            boolean click = event.type == SWT.MouseUp && event.button == 1;
            boolean key =
                event.type == SWT.KeyDown
                    && (event.keyCode == SWT.CR
                        || event.keyCode == SWT.KEYPAD_CR
                        || event.character == ' ');
            if (click || key) {
              gesture = true;
              ui.timerExec(GESTURE_MILLIS, gestureEnd);
            }
          }
        };
    browser.addListener(SWT.MouseUp, gestures);
    browser.addListener(SWT.KeyDown, gestures);
  }

  /** Compares two locations after percent-decoding. */
  private static boolean same(String location, String wanted) {
    return decode(location).equals(decode(wanted));
  }

  private static String decode(String location) {
    try {
      return URLDecoder.decode(location == null ? "" : location, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return location;
    }
  }

  /**
   * Returns the pane's control.
   *
   * @return the root composite
   */
  Control control() {
    return root;
  }

  /** Focuses the browser. */
  void focus() {
    if (!browser.isDisposed()) {
      browser.setFocus();
    }
  }

  /**
   * Shows Browser content: its page replaces the one shown, and the last id asked for is scrolled
   * to once it loads. Hides the size notice.
   *
   * @param next content with HTML
   */
  void showRendered(MarkdownPreviewContent next) {
    if (root.isDisposed()) {
      return;
    }
    setNotice(null);
    content = next;
    load();
  }

  private void load() {
    if (content == null || content.html() == null) {
      return;
    }
    String page = MarkdownPage.page("", content.html().body(), palette);
    loaded = false;
    expected = BLANK;
    call(
        new Runnable() {
          @Override
          public void run() {
            browser.setText(page, false);
          }
        });
  }

  /**
   * Shows the size notice with its render action.
   *
   * @param chars the document's length
   * @param limit the longest document rendered on its own
   */
  void showSizeNotice(int chars, int limit) {
    if (!root.isDisposed()) {
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
  boolean noticeShown() {
    return notice.getVisible();
  }

  /**
   * Scrolls to the element showing a rendered line: the last element starting at or above the
   * line's source line.
   *
   * @param line a line of the shown content's render
   */
  void scrollToLine(int line) {
    if (content != null && content.html() != null) {
      String id = content.html().idAt(content.rendered().sourceLine(line));
      if (id != null) {
        scrollToAnchor(id);
      }
    }
  }

  /**
   * Scrolls to the element with an id.
   *
   * @param id the id
   */
  void scrollToAnchor(String id) {
    if (!PlatformTraits.PLATFORM.scrollsBrowserToFragments()) {
      return;
    }
    anchor = id;
    if (loaded) {
      scroll(id);
    }
  }

  private void scroll(String id) {
    final String target =
        BLANK + "#" + URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    expected = target;
    call(
        new Runnable() {
          @Override
          public void run() {
            browser.setUrl(target);
          }
        });
  }

  /**
   * Recolors the page: the shown content loads again with the palette's stylesheet.
   *
   * @param next the palette
   */
  void setPalette(Palette next) {
    palette = next;
    if (!root.isDisposed()) {
      load();
    }
  }

  /** Runs a browser call; an exception records the failure and runs the failure action once. */
  private void call(Runnable task) {
    if (failed || browser.isDisposed()) {
      return;
    }
    try {
      task.run();
    } catch (SWTException | SWTError e) {
      failed = true;
      LOG.info("Browser preview failed: {}", LogText.oneLine(e.getMessage()));
      BrowserPreview.failed(browser.getDisplay(), e.getMessage());
      failure.run();
    }
  }

  /**
   * Returns the shown content.
   *
   * @return the content, or null before the first
   */
  MarkdownPreviewContent content() {
    return content;
  }

  /**
   * Returns the browser control.
   *
   * @return the browser
   */
  Browser browser() {
    return browser;
  }

  /** Disposes the pane. */
  void dispose() {
    if (!root.isDisposed()) {
      root.dispose();
    }
  }
}
