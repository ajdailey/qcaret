package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.theme.Palette;
import io.github.ajdailey.qcaret.app.view.MarkdownHtml;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewContent;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewInput;
import io.github.ajdailey.qcaret.app.view.MarkdownPreviewView;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.TitleEvent;
import org.eclipse.swt.browser.TitleListener;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Browser preview pane with the platform's real browser: the content's page loads as built, a
 * {@code setText} page reads no {@code file:} URL, a page with remote resources makes no request, a
 * navigation is canceled and forwarded only after a click, the pane's own fragment scroll is the
 * one navigation let through, and the failure path: a throwing call, the load bound, and a failure
 * during the pane's own call running after it returns. Each outcome is driven directly, never raced
 * against a real load. Skips, with the reason, where {@link BrowserHosts#assumeBrowser} finds no
 * browser.
 */
@Tag("swt")
class BrowserPaneTest {
  private static final Duration BOUND = Duration.ofSeconds(30);

  /** Time a loaded page gets to start any request or navigation it would make. */
  private static final long SETTLE_NANOS = 2_000_000_000L;

  @TempDir Path temp;

  private Display display;
  private Shell shell;
  private final List<String> followed = new CopyOnWriteArrayList<String>();
  private int failures;
  private int failureActions;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setLayout(new FillLayout());
    shell.setSize(700, 500);
    BrowserHosts.assumeBrowser(shell);
  }

  @AfterEach
  void close() {
    if (display != null && !display.isDisposed()) {
      display.dispose();
    }
  }

  private final MarkdownPreviewInput input =
      new MarkdownPreviewInput() {
        @Override
        public void attach(MarkdownPreviewView view) {
          // Not attached.
        }

        @Override
        public void renderRequested() {
          // No notice.
        }

        @Override
        public void previewScrolled(int line) {
          // The browser reports no scroll.
        }

        @Override
        public void clicked(int offset) {
          // Basic only.
        }

        @Override
        public void linkFollowed(String location) {
          followed.add(location);
        }

        @Override
        public void browserFailed() {
          failures++;
        }
      };

  private BrowserPane pane() {
    BrowserPane pane =
        new BrowserPane(
            shell,
            input,
            GuideWindow.BROWSER,
            new SwtUiDispatcher(display),
            new Runnable() {
              @Override
              public void run() {
                failureActions++;
              }
            });
    shell.layout(true, true);
    shell.open();
    return pane;
  }

  private static MarkdownPreviewContent content(String body, int[] lines, List<String> ids) {
    return new MarkdownPreviewContent(
        new MarkdownRenderer().render("text\n", null, new CancellationToken()),
        List.<int[]>of(),
        new int[0],
        List.<MarkdownImageRequest>of(),
        new MarkdownHtml(body, lines, ids, MarkdownPage.page("", body, Palette.LIGHT)));
  }

  private void await(BooleanSupplier condition, String what) {
    assertTrue(EventLoop.runUntil(display, condition, BOUND), what);
  }

  private void settle() {
    final long until = System.nanoTime() + SETTLE_NANOS;
    EventLoop.runUntil(
        display,
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return System.nanoTime() > until;
          }
        },
        BOUND);
  }

  private void awaitLoaded(Browser browser) {
    final boolean[] done = new boolean[1];
    browser.addProgressListener(
        new ProgressAdapter() {
          @Override
          public void completed(ProgressEvent event) {
            done[0] = true;
          }
        });
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return done[0];
          }
        },
        "page loaded");
  }

  @Test
  void aSetTextPageReadsNoFileUrl() throws Exception {
    Path png = temp.resolve("dot.png");
    ImageLoader loader = new ImageLoader();
    loader.data =
        new ImageData[] {new ImageData(40, 30, 24, new PaletteData(0xFF0000, 0xFF00, 0xFF))};
    loader.save(png.toString(), SWT.IMAGE_PNG);
    String img = "<img id=\"i\" src=\"" + png.toUri() + "\">";
    // JavaScript on in this browser only, to read the image's width.
    Browser browser = new Browser(shell, SWT.NONE);
    shell.open();
    assertEquals(0.0, width(browser, MarkdownPage.page("", img, Palette.LIGHT), false));
    String plain = "<html><body>" + img + "</body></html>";
    assertEquals(40.0, width(browser, plain, true), "a trusted page reads file:");
    // GTK: the untrusted page's about:blank origin blocks file:. cocoa and win32 load it (CI on
    // aa1db2c), so the content policy is the barrier there.
    double untrusted = "gtk".equals(SWT.getPlatform()) ? 0.0 : 40.0;
    assertEquals(untrusted, width(browser, plain, false), "untrusted, no policy");
  }

  private Object width(Browser browser, String page, boolean trusted) {
    browser.setText(page, trusted);
    awaitLoaded(browser);
    settle();
    return browser.evaluate("return document.getElementById('i').naturalWidth;");
  }

  @Test
  void aPageWithRemoteResourcesMakesNoRequest() throws Exception {
    final List<String> hits = new CopyOnWriteArrayList<String>();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            hits.add(exchange.getRequestURI().toString());
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
          }
        });
    server.start();
    try {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      String control = remoteBody(origin + "/control");
      String body = remoteBody(origin + "/pane");
      // The control: the same resources without the policy reach the server, and its own
      // canceled meta refresh does not.
      Browser plain = new Browser(shell, SWT.NONE);
      plain.setJavascriptEnabled(false);
      plain.addLocationListener(
          new LocationAdapter() {
            @Override
            public void changing(LocationEvent event) {
              event.doit = event.location.equals("about:blank");
            }
          });
      shell.open();
      plain.setText("<html><body>" + control + "</body></html>", false);
      awaitLoaded(plain);
      settle();
      System.out.println("Requests from the control page: " + hits);
      assertFalse(hits.isEmpty(), "the control page reached the server");
      assertFalse(hits.contains("/control/refresh"), "a canceled navigation makes a request");
      plain.dispose();
      BrowserPane pane = pane();
      pane.showRendered(content(body, new int[0], List.<String>of()));
      awaitLoaded(pane.browser());
      settle();
      List<String> fromPane = new ArrayList<String>();
      for (String hit : hits) {
        if (hit.startsWith("/pane/")) {
          fromPane.add(hit);
        }
      }
      assertEquals(List.of(), fromPane);
      assertEquals(List.of(), followed, "a meta refresh is no click");
      assertEquals(0, failures);
    } finally {
      server.stop(0);
    }
  }

  private static String remoteBody(String remote) {
    return "<img src=\""
        + remote
        + "/img.png\"><link rel=\"stylesheet\" href=\""
        + remote
        + "/s.css\"><iframe src=\""
        + remote
        + "/frame\"></iframe><div style=\"background: url('"
        + remote
        + "/bg.png'); width: 9px; height: 9px\"></div><video src=\""
        + remote
        + "/v.mp4\"></video><object data=\""
        + remote
        + "/o\"></object><style>@import url('"
        + remote
        + "/imp.css'); @font-face { font-family: m; src: url('"
        + remote
        + "/f.woff'); } p { font-family: m; }</style><p>text</p>"
        + "<meta http-equiv=\"refresh\" content=\"0; url="
        + remote
        + "/refresh\"><script src=\""
        + remote
        + "/s.js\"></script>";
  }

  @Test
  void aClickedLinkIsCanceledAndForwarded() {
    assumeTrue("gtk".equals(SWT.getPlatform()), "clicks posted through Display.post on GTK 3 only");
    BrowserPane pane = pane();
    final List<String> allowed = new ArrayList<String>();
    pane.browser()
        .addLocationListener(
            new LocationAdapter() {
              @Override
              public void changing(LocationEvent event) {
                if (event.doit) {
                  allowed.add(event.location);
                }
              }
            });
    pane.showRendered(
        content(
            "<p style=\"margin: 0; font-size: 40px\"><a id=\"go\""
                + " href=\"https://example.invalid/page\">GO GO GO</a></p>",
            new int[0],
            List.<String>of()));
    awaitLoaded(pane.browser());
    shell.forceActive();
    settle();
    assertEquals(List.of(), followed);
    Point at = pane.browser().toDisplay(30, 25);
    post(SWT.MouseMove, at, 0);
    post(SWT.MouseDown, at, 1);
    post(SWT.MouseUp, at, 1);
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !followed.isEmpty();
          }
        },
        "the click reached linkFollowed");
    assertEquals(List.of("https://example.invalid/page"), followed);
    assertEquals(List.of("about:blank"), allowed);
  }

  @Test
  void theFragmentScrollIsTheOneNavigationLetThrough() {
    assumeTrue(PlatformTraits.PLATFORM.scrollsBrowserToFragments(), "no fragment scroll");
    BrowserPane pane = pane();
    final List<String> allowed = new ArrayList<String>();
    pane.browser()
        .addLocationListener(
            new LocationAdapter() {
              @Override
              public void changing(LocationEvent event) {
                if (event.doit) {
                  allowed.add(event.location);
                }
              }
            });
    StringBuilder body = new StringBuilder("<h1 id=\"top\">Top</h1>");
    for (int i = 1; i < 200; i++) {
      body.append("<p id=\"qcaret-line-").append(i).append("\">line ").append(i).append("</p>");
    }
    int[] lines = new int[200];
    List<String> ids = new ArrayList<String>();
    ids.add("top");
    for (int i = 1; i < 200; i++) {
      lines[i] = i;
      ids.add("qcaret-line-" + i);
    }
    pane.showRendered(content(body.toString(), lines, ids));
    awaitLoaded(pane.browser());
    pane.scrollToAnchor("qcaret-line-150");
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return allowed.size() == 2;
          }
        },
        "the scroll navigated, allowed " + allowed);
    settle();
    assertEquals(List.of("about:blank", "about:blank#qcaret-line-150"), allowed);
    assertEquals(List.of(), followed);
    assertNotNull(pane.content());
    assertFalse(pane.noticeShown());
  }

  @Test
  void aThrowingCallRunsTheFailureActionOnce() {
    BrowserPane pane = pane();
    pane.call(
        new Runnable() {
          @Override
          public void run() {
            throw new SWTException("Mock browser call failure");
          }
        });
    assertEquals(1, failureActions);
    String reason = BrowserPreview.unavailable(display);
    assertNotNull(reason);
    assertTrue(reason.contains("Mock browser call failure"), reason);
    final boolean[] ran = new boolean[1];
    pane.call(
        new Runnable() {
          @Override
          public void run() {
            ran[0] = true;
          }
        });
    assertFalse(ran[0], "no call after a failure");
    assertEquals(1, failureActions);
  }

  @Test
  void theLoadBoundExpiringRunsTheFailureAction() {
    BrowserPane pane = pane();
    pane.bound().arm();
    pane.bound().run();
    assertEquals(1, failureActions);
    String reason = BrowserPreview.unavailable(display);
    assertNotNull(reason);
    assertTrue(reason.contains(UiText.MARKDOWN_BROWSER_TIMEOUT.format(15)), reason);
    assertFalse(pane.bound().armed());
  }

  @Test
  void aFailureDuringTheOwnCallRunsAfterItReturns() {
    final BrowserPane pane = pane();
    final int[] during = new int[] {-1};
    pane.call(
        new Runnable() {
          @Override
          public void run() {
            pane.bound().arm();
            pane.bound().run();
            pane.call(
                new Runnable() {
                  @Override
                  public void run() {
                    throw new SWTException("Mock nested failure");
                  }
                });
            during[0] = failureActions;
          }
        });
    assertEquals(0, during[0]);
    assertEquals(1, failureActions);
    String reason = BrowserPreview.unavailable(display);
    assertTrue(reason.contains(UiText.MARKDOWN_BROWSER_TIMEOUT.format(15)), reason);
  }

  @Test
  void theContentsPageLoadsAsBuilt() {
    BrowserPane pane = pane();
    final List<String> titles = new CopyOnWriteArrayList<String>();
    pane.browser()
        .addTitleListener(
            new TitleListener() {
              @Override
              public void changed(TitleEvent event) {
                titles.add(event.title);
              }
            });
    String page = "<html><head><title>Mock built page</title></head><body>x</body></html>";
    pane.showRendered(
        new MarkdownPreviewContent(
            new MarkdownRenderer().render("text\n", null, new CancellationToken()),
            List.<int[]>of(),
            new int[0],
            List.<MarkdownImageRequest>of(),
            new MarkdownHtml("y", new int[0], List.<String>of(), page)));
    await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return titles.contains("Mock built page");
          }
        },
        "the page's own title, saw " + titles);
    assertEquals(0, failureActions);
  }

  @Test
  void aLoadedPageMeetsTheBound() {
    BrowserPane pane = pane();
    pane.showRendered(content("<p>text</p>", new int[0], List.<String>of()));
    assertTrue(pane.bound().armed());
    awaitLoaded(pane.browser());
    assertFalse(pane.bound().armed());
    assertEquals(0, failureActions);
  }

  private void post(int type, Point at, int button) {
    Event event = new Event();
    event.type = type;
    event.x = at.x;
    event.y = at.y;
    event.button = button;
    assertTrue(display.post(event));
    while (display.readAndDispatch()) {
      // Delivers the event.
    }
  }
}
