package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
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
 * The Browser Markdown preview of one tab: an SWT {@link Browser} showing a render's page, built
 * off the UI thread ({@link io.github.ajdailey.qcaret.app.view.MarkdownHtml#page}), through {@code
 * setText(page, false)}, with the size notice above it.
 *
 * <ul>
 *   <li>JavaScript is off, and the page's content security policy ({@link MarkdownPage#POLICY})
 *       loads nothing but inline styles and {@code data:} images.
 *   <li>Every navigation is canceled except the page this pane loads and the {@code about:blank#id}
 *       scroll it asks for. {@link LinkGestures} sends a canceled navigation that a primary-button
 *       release or an Enter or Space key on the browser started, or every top-frame one where
 *       {@link PlatformTraits#followsLinksWithoutGesture} holds, to {@link
 *       MarkdownPreviewInput#linkFollowed}; any other one, such as a meta refresh, is dropped.
 *   <li>Where {@link PlatformTraits#blankLinksReplacePage} holds, an empty {@code about:blank} page
 *       the pane did not load is replaced by the shown content's page once, after it loads; its
 *       {@code completed} event neither meets a bound nor scrolls.
 *   <li>Scrolling to a line or an anchor navigates to {@code about:blank#id} where {@link
 *       PlatformTraits#scrollsBrowserToFragments} holds, after the page loaded; a new page returns
 *       to the last id asked for. The browser's own scroll is not reported: with JavaScript off the
 *       page position cannot be read.
 *   <li>An {@link SWTException} or {@link SWTError} from a browser call, a page whose navigation
 *       has not started within {@link GuideWindow#START_BOUND_MILLIS} of {@code setText}, or a page
 *       not loaded within {@link GuideWindow#LOAD_BOUND_MILLIS} of its navigation starting, runs
 *       the failure action once; no browser exception leaves this class. A failure while this
 *       pane's own browser call is on the stack runs after that call returns: on Cocoa an SWT timer
 *       runs inside a nested run loop a native call spins.
 * </ul>
 *
 * Call on the UI thread.
 */
final class BrowserPane {
  private static final Logger LOG = LoggerFactory.getLogger(BrowserPane.class);

  private static final String BLANK = "about:blank";

  private final Composite root;
  private final Composite notice;
  private final Label noticeText;
  private final Browser browser;
  private final MarkdownPreviewInput input;
  private final Runnable failure;
  private final GuideWindow.LoadBound start;
  private final GuideWindow.LoadBound bound;
  private final LinkGestures gestures;
  private MarkdownPreviewContent content;
  private String expected;
  private String committing;
  private boolean stray;
  private boolean restored;
  private boolean loaded;
  private boolean failed;
  private String anchor;
  private int calls;
  private String deferredFailure;

  /**
   * Creates the pane, empty.
   *
   * @param parent the tab's frame
   * @param input receives links and the render request
   * @param factory creates the browser control
   * @param ui runs the gesture's time limit and the start and load bounds
   * @param failure runs once when a browser call fails, after this pane recorded it
   * @throws SWTException when the browser control cannot be created
   * @throws SWTError when the browser control cannot be created
   */
  BrowserPane(
      Composite parent,
      final MarkdownPreviewInput input,
      GuideWindow.BrowserFactory factory,
      UiDispatcher ui,
      Runnable failure) {
    this.input = input;
    this.failure = failure;
    this.start = timeout(ui, GuideWindow.START_BOUND_MILLIS);
    this.bound = timeout(ui, GuideWindow.LOAD_BOUND_MILLIS);
    this.gestures =
        new LinkGestures(
            ui,
            PlatformTraits.PLATFORM.followsLinksWithoutGesture(),
            new Consumer<String>() {
              @Override
              public void accept(String location) {
                input.linkFollowed(location);
              }
            });
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

  private GuideWindow.LoadBound timeout(UiDispatcher dispatcher, final int millis) {
    return new GuideWindow.LoadBound(
        dispatcher,
        millis,
        new Runnable() {
          @Override
          public void run() {
            fail(UiText.MARKDOWN_BROWSER_TIMEOUT.format(millis / 1000));
          }
        });
  }

  private void listen() {
    browser.addLocationListener(
        new LocationAdapter() {
          @Override
          public void changing(LocationEvent event) {
            if (expected != null && same(event.location, expected)) {
              committing = expected;
              expected = null;
              if (start.armed()) {
                start.met();
                bound.arm();
              }
              return;
            }
            event.doit = false;
            gestures.canceled(event.location, event.top);
          }

          @Override
          public void changed(LocationEvent event) {
            if (!event.top || !PlatformTraits.PLATFORM.blankLinksReplacePage()) {
              return;
            }
            if (committing != null && same(event.location, committing)) {
              committing = null;
              stray = false;
            } else if (BLANK.equals(event.location)) {
              stray = true;
              LOG.debug("Markdown preview page replaced by an empty one");
            }
          }
        });
    browser.addProgressListener(
        new ProgressAdapter() {
          @Override
          public void completed(ProgressEvent event) {
            if (stray) {
              stray = false;
              if (!restored && page() != null) {
                restored = true;
                load(page());
              }
              return;
            }
            restored = false;
            loaded = true;
            start.met();
            bound.met();
            if (anchor != null) {
              scroll(anchor);
            }
          }
        });
    Listener gesture =
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
              gestures.gesture(click ? LinkGestures.Source.CLICK : LinkGestures.Source.KEY);
            }
          }
        };
    browser.addListener(SWT.MouseUp, gesture);
    browser.addListener(SWT.KeyDown, gesture);
  }

  /**
   * Reports whether a navigation is the one the pane asked for: the same text before the first
   * {@code #}, and the same fragment after percent-decoding. A {@code #} escaped as {@code %23}
   * does not match: that URL has no fragment, and WebKit loads it as another page.
   *
   * @param location the location of a navigation
   * @param wanted the location the pane loads
   * @return true when they match
   */
  static boolean same(String location, String wanted) {
    if (location == null) {
      return false;
    }
    int hash = location.indexOf('#');
    int wantedHash = wanted.indexOf('#');
    if (hash < 0 || wantedHash < 0) {
      return location.equals(wanted);
    }
    return location.substring(0, hash).equals(wanted.substring(0, wantedHash))
        && decode(location.substring(hash)).equals(decode(wanted.substring(wantedHash)));
  }

  private static String decode(String location) {
    try {
      return URLDecoder.decode(location, StandardCharsets.UTF_8);
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
   * @param next content with HTML and its page
   */
  void showRendered(MarkdownPreviewContent next) {
    if (root.isDisposed()) {
      return;
    }
    setNotice(null);
    content = next;
    if (page() != null) {
      load(page());
    }
  }

  /** The shown content's page, or null. */
  private String page() {
    return content == null || content.html() == null ? null : content.html().page();
  }

  private void load(final String page) {
    loaded = false;
    expected = BLANK;
    bound.met();
    start.arm();
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
   * Runs a browser call. An exception, or a failure while the call is on the stack, records the
   * failure and runs the failure action once after the outermost call returns.
   *
   * @param task calls the browser
   */
  void call(Runnable task) {
    if (failed || browser.isDisposed()) {
      return;
    }
    calls++;
    try {
      task.run();
    } catch (SWTException | SWTError e) {
      if (deferredFailure == null) {
        deferredFailure = e.getMessage() == null ? "" : e.getMessage();
      }
    } finally {
      calls--;
    }
    if (calls == 0 && deferredFailure != null) {
      String reason = deferredFailure;
      deferredFailure = null;
      fail(reason);
    }
  }

  /** Records the failure and runs the failure action once, or defers it past this pane's call. */
  private void fail(String reason) {
    if (calls > 0) {
      if (deferredFailure == null) {
        deferredFailure = reason;
      }
      return;
    }
    if (failed || root.isDisposed()) {
      return;
    }
    failed = true;
    start.met();
    bound.met();
    LOG.info("Browser preview failed: {}", LogText.oneLine(reason));
    BrowserPreview.failed(root.getDisplay(), reason);
    failure.run();
  }

  /**
   * Returns the start bound.
   *
   * @return the bound from the current {@code setText} to its navigation
   */
  GuideWindow.LoadBound start() {
    return start;
  }

  /**
   * Returns the load bound.
   *
   * @return the bound from the current page's navigation to its {@code completed} event
   */
  GuideWindow.LoadBound bound() {
    return bound;
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
    start.met();
    bound.met();
    if (!root.isDisposed()) {
      root.dispose();
    }
  }
}
