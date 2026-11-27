package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ajdailey.qcaret.app.about.UserGuide;
import io.github.ajdailey.qcaret.app.editor.MarkdownPage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The built user guide in the guide window, with remote resources added after its {@code <body>}:
 * the guide without its content security policy reaches a server on an ephemeral loopback port, and
 * the guide as built makes no request to it.
 */
@Tag("swt")
class GuidePolicyTest {
  /** Covers a page load, the first WebView2 start on a CI runner included. */
  private static final Duration BOUND = Duration.ofSeconds(90);

  /** Time a blocked load gets to reach the server anyway. */
  private static final long SETTLE_NANOS = 2_000_000_000L;

  @TempDir Path temp;

  private final List<Path> fallbacks = new CopyOnWriteArrayList<Path>();
  private Display display;
  private Shell shell;

  @BeforeEach
  void open() {
    display = new Display();
    shell = new Shell(display);
    shell.setSize(400, 300);
  }

  @AfterEach
  void close() {
    display.dispose();
  }

  @Test
  void theGuideWithRemoteResourcesMakesNoRequest() throws Exception {
    BrowserHosts.assumeBrowser(shell);
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
      String guide =
          Files.readString(UserGuide.writeCopy(temp.resolve("settings")), StandardCharsets.UTF_8);
      String policy =
          "<meta http-equiv=\"Content-Security-Policy\" content=\"" + MarkdownPage.POLICY + "\">\n";
      assertTrue(guide.contains(policy), "the built guide carries the policy");
      shell.open();
      show(withRemote(guide.replace(policy, ""), origin + "/control"), "control.html");
      assertTrue(
          EventLoop.runUntil(
              display,
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return !hits.isEmpty();
                }
              },
              BOUND),
          "the control guide reached the server");
      show(withRemote(guide, origin + "/guide"), "guide.html");
      settle();
      System.out.println("Requests on " + SWT.getPlatform() + ": " + hits);
      List<String> fromGuide = new ArrayList<String>();
      for (String hit : hits) {
        if (hit.startsWith("/guide/")) {
          fromGuide.add(hit);
        }
      }
      assertEquals(List.of(), fromGuide);
      assertEquals(List.of(), fallbacks);
    } finally {
      server.stop(0);
    }
  }

  /** Writes a page, shows it in a guide window and waits until it has loaded. */
  private void show(String page, String name) throws IOException {
    Path file = Files.writeString(temp.resolve(name), page, StandardCharsets.UTF_8);
    final GuideWindow window =
        GuideWindow.create(
            shell,
            file,
            new Consumer<Path>() {
              @Override
              public void accept(Path failed) {
                fallbacks.add(failed);
              }
            },
            GuideWindow.BROWSER,
            new UnboundDispatcher(new SwtUiDispatcher(display)));
    assertNotNull(window, "a guide window");
    window.open();
    assertTrue(
        EventLoop.runUntil(
            display,
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return window.loaded();
              }
            },
            BOUND),
        "location " + window.location());
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

  /** Returns the page with an image, a stylesheet, a background, a font and media under a base. */
  private static String withRemote(String page, String remote) {
    String resources =
        "<img src=\""
            + remote
            + "/img.png\"><link rel=\"stylesheet\" href=\""
            + remote
            + "/s.css\"><div style=\"background: url('"
            + remote
            + "/bg.png'); width: 9px; height: 9px\"></div><video src=\""
            + remote
            + "/v.mp4\"></video><style>@font-face { font-family: m; src: url('"
            + remote
            + "/f.woff'); } p { font-family: m; }</style><p>text</p>\n";
    int body = page.indexOf("<body>\n");
    assertTrue(body > 0, "the guide's body");
    int at = body + "<body>\n".length();
    return page.substring(0, at) + resources + page.substring(at);
  }
}
