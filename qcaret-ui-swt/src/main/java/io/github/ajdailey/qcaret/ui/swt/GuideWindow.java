package io.github.ajdailey.qcaret.ui.swt;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A modeless window showing the user guide in an SWT {@link Browser} with JavaScript off. The
 * browser loads only the guide's file: every other location, a web address included, is refused,
 * and anchors within the page work.
 *
 * <p>An {@link SWTException} or {@link SWTError} from a browser call, a guide whose navigation has
 * not started within {@link #START_BOUND_MILLIS} of {@code setUrl}, or a guide not loaded within
 * {@link #LOAD_BOUND_MILLIS} of its navigation starting, closes the window and passes the guide's
 * file to the fallback; no browser exception reaches the caller. The bounds run on {@link
 * UiDispatcher#timerExec}, so the UI thread never waits for the browser.
 *
 * <p>A bound that expires while this window's own browser or shell call is on the stack closes the
 * window after that call returns: on Cocoa an SWT timer runs inside any nested run loop a native
 * call spins, which would dispose the browser inside its own call.
 */
final class GuideWindow {
  private static final Logger LOG = LoggerFactory.getLogger(GuideWindow.class);

  /**
   * Time from a load request to the page's navigation, which on win32 includes the WebView2 start
   * of a new browser control.
   */
  static final int START_BOUND_MILLIS = 60_000;

  /** Time from the page's navigation to the loaded page. */
  static final int LOAD_BOUND_MILLIS = 15_000;

  /** Creates the browser widget in the window. */
  interface BrowserFactory {
    Browser create(Composite parent);
  }

  static final BrowserFactory BROWSER =
      new BrowserFactory() {
        @Override
        public Browser create(Composite parent) {
          return new Browser(parent, SWT.NONE);
        }
      };

  private final Shell shell;
  private final Consumer<Path> fallback;
  private final LoadBound start;
  private final LoadBound bound;
  private final Browser browser;
  private Path file;
  private String location;
  private boolean completed;
  private boolean failed;
  private int calls;
  private int loads;
  private String deferredFailure;

  private GuideWindow(
      Shell shell, Browser browser, Path file, Consumer<Path> fallback, UiDispatcher ui) {
    this.shell = shell;
    this.browser = browser;
    this.file = file;
    this.fallback = fallback;
    this.start = timeout(ui, START_BOUND_MILLIS, "navigation not started");
    this.bound = timeout(ui, LOAD_BOUND_MILLIS, "not loaded");
  }

  private LoadBound timeout(UiDispatcher ui, final int millis, final String what) {
    return new LoadBound(
        ui,
        millis,
        new Runnable() {
          @Override
          public void run() {
            fail(what + " within " + millis + " ms");
          }
        });
  }

  /**
   * Creates the window and starts loading the guide.
   *
   * @param parent the main window
   * @param file the guide's HTML file
   * @param fallback receives the guide's file when the window fails after this returns
   * @return the window, not yet open, or null when the browser fails here, the fallback not called
   */
  static GuideWindow create(Shell parent, Path file, Consumer<Path> fallback) {
    return create(parent, file, fallback, BROWSER, new SwtUiDispatcher(parent.getDisplay()));
  }

  /**
   * Creates the window with a given browser factory and dispatcher.
   *
   * @param parent the main window
   * @param file the guide's HTML file
   * @param fallback receives the guide's file when the window fails after this returns
   * @param factory creates the browser widget
   * @param ui runs the start and load bounds
   * @return the window, not yet open, or null when the browser fails here, the fallback not called
   */
  static GuideWindow create(
      Shell parent, Path file, Consumer<Path> fallback, BrowserFactory factory, UiDispatcher ui) {
    Shell shell = Dialogs.shell(parent, UiText.GUIDE_TITLE.text(), false);
    GuideWindow window = null;
    try {
      Browser browser = factory.create(shell);
      window = new GuideWindow(shell, browser, file, fallback, ui);
      window.calls++;
      try {
        browser.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        browser.setJavascriptEnabled(false);
        window.listen();
        Composite buttons = Dialogs.buttonRow(shell, 1);
        Button close = Dialogs.button(buttons, UiText.BUTTON_CLOSE.text(), Dialogs.closer(shell));
        shell.setDefaultButton(close);
        window.load(file);
      } finally {
        // A failure deferred here runs when open returns.
        window.calls--;
      }
      return window;
    } catch (SWTException | SWTError e) {
      LOG.info("No browser for the user guide: {}", LogText.oneLine(e.getMessage()));
      if (window != null) {
        window.start.met();
        window.bound.met();
      }
      dispose(shell);
      return null;
    }
  }

  private void listen() {
    browser.addLocationListener(
        new LocationAdapter() {
          @Override
          public void changing(LocationEvent event) {
            if (!allowed(event.location, file)) {
              event.doit = false;
              LOG.debug("User guide refused {}", LogText.oneLine(event.location));
            } else if (start.armed()
                && event.location != null
                && event.location.startsWith("file:")) {
              start.met();
              bound.arm();
            }
          }

          @Override
          public void changed(LocationEvent event) {
            if (event.top) {
              location = event.location;
              settle();
            }
          }
        });
    browser.addProgressListener(
        new ProgressAdapter() {
          @Override
          public void completed(ProgressEvent event) {
            completed = true;
            settle();
          }
        });
  }

  /** Starts a load of the guide and the start bound on it; a browser exception propagates. */
  private void load(Path guide) {
    file = guide;
    location = null;
    completed = false;
    loads++;
    bound.met();
    start.arm();
    browser.setUrl(guide.toUri().toString());
  }

  private void settle() {
    if (loaded()) {
      start.met();
      bound.met();
    }
  }

  /**
   * Closes the window once and hands the guide's file to the fallback, or defers that until this
   * window's own call returns.
   */
  private void fail(String reason) {
    if (failed || shell.isDisposed()) {
      return;
    }
    if (calls > 0) {
      deferredFailure = reason;
      return;
    }
    failed = true;
    LOG.info("User guide window closed, {}; opening the guide elsewhere", LogText.oneLine(reason));
    start.met();
    bound.met();
    dispose(shell);
    fallback.accept(file);
  }

  private static void dispose(Shell shell) {
    try {
      shell.dispose();
    } catch (SWTException | SWTError e) {
      LOG.debug("Closing the user guide window: {}", LogText.oneLine(e.getMessage()));
    }
  }

  /**
   * Reports whether the browser may load a location: the guide's file, with or without an anchor,
   * or the blank page a browser starts on.
   *
   * @param location the location the browser is about to load
   * @param file the guide's file
   * @return true for the guide itself
   */
  static boolean allowed(String location, Path file) {
    if (location == null || location.isEmpty() || location.equals("about:blank")) {
      return true;
    }
    try {
      URI uri = new URI(location);
      if (!"file".equals(uri.getScheme())) {
        return false;
      }
      URI page = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
      return Path.of(page).equals(file.toAbsolutePath());
    } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
      return false;
    }
  }

  /** Opens the window; a browser exception closes it and takes the fallback. */
  void open() {
    calls++;
    try {
      Dialogs.open(shell, 900, 700);
    } catch (SWTException | SWTError e) {
      fail(e.getMessage());
    } finally {
      returned();
    }
  }

  /** Ends one of this window's own calls and runs a failure deferred during it. */
  private void returned() {
    calls--;
    if (calls == 0 && deferredFailure != null) {
      String reason = deferredFailure;
      deferredFailure = null;
      fail(reason);
    }
  }

  /**
   * Shows the guide's file again and brings the window to the front; a browser exception closes the
   * window and takes the fallback.
   *
   * @param guide the guide's HTML file
   */
  void show(Path guide) {
    calls++;
    try {
      load(guide);
      shell.setActive();
    } catch (SWTException | SWTError e) {
      file = guide;
      fail(e.getMessage());
    } finally {
      returned();
    }
  }

  Shell shell() {
    return shell;
  }

  /**
   * Returns the start bound.
   *
   * @return the bound from the current load request to the guide's navigation
   */
  LoadBound start() {
    return start;
  }

  /**
   * Returns the load bound.
   *
   * @return the bound from the guide's navigation to the loaded page
   */
  LoadBound bound() {
    return bound;
  }

  /**
   * Returns the top-level location the browser last showed, as its {@code changed} event reported
   * it; reading it does not call the browser.
   *
   * @return the location, or null before the current load showed one
   */
  String location() {
    return location;
  }

  /**
   * Returns the number of loads started.
   *
   * @return the count, 1 after the window's own first load
   */
  int loads() {
    return loads;
  }

  /**
   * Reports whether the browser finished loading the guide's file.
   *
   * @return true after the current load completed on a {@code file:} location
   */
  boolean loaded() {
    return completed && location != null && location.startsWith("file:");
  }

  /**
   * A bound on one load, run by {@link UiDispatcher#timerExec}: {@link #arm} starts or restarts it,
   * {@link #met} disarms it, and expiry while armed runs the expiry action once. Call on the UI
   * thread.
   */
  static final class LoadBound implements Runnable {
    private final UiDispatcher ui;
    private final int millis;
    private final Runnable expired;
    private boolean armed;

    LoadBound(UiDispatcher ui, int millis, Runnable expired) {
      this.ui = ui;
      this.millis = millis;
      this.expired = expired;
    }

    /** Arms the bound; the timer restarts when it is already scheduled. */
    void arm() {
      armed = true;
      ui.timerExec(millis, this);
    }

    /** Disarms the bound; a timer still scheduled then does nothing. */
    void met() {
      armed = false;
    }

    boolean armed() {
      return armed;
    }

    @Override
    public void run() {
      if (armed) {
        armed = false;
        expired.run();
      }
    }
  }
}
