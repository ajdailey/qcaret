package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import java.util.function.Consumer;
import org.eclipse.swt.browser.Browser;

/**
 * Embedded browser differences, one implementation per platform, from {@link
 * PlatformTraits#browserBehavior}: whether a {@code Browser} may be created, and how the Markdown
 * preview treats links, anchors, a replaced page and menu mnemonics. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Browser</th><th>Ungestured link</th>
 *       <th>Anchor scroll</th><th>Blank page check</th><th>Menu mnemonics</th></tr>
 *   <tr><td>win32</td><td>{@link Win32BrowserBehavior}</td><td>yes</td><td>followed</td><td>no</td>
 *       <td>no</td><td>sent to the shell</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaBrowserBehavior}</td><td>yes</td><td>dropped</td><td>no</td>
 *       <td>no</td><td>native</td></tr>
 *   <tr><td>GTK 3</td><td>{@link Gtk3BrowserBehavior}</td><td>yes</td><td>dropped</td><td>yes</td>
 *       <td>yes</td><td>native</td></tr>
 *   <tr><td>GTK 4</td><td>{@link Gtk4BrowserBehavior}</td><td>no</td><td>dropped</td><td>yes</td>
 *       <td>yes</td><td>native</td></tr>
 * </table>
 */
public interface BrowserBehavior {
  /** The running platform's browser behavior. */
  BrowserBehavior PLATFORM = PlatformTraits.PLATFORM.browserBehavior();

  /**
   * Returns why no {@code Browser} may be created. WebKitGTK 6.0 aborts the JVM when the browser
   * starts under GTK 4 ("Failed to fully launch dbus-proxy").
   *
   * @return the reason on GTK 4, else null
   */
  String unavailable();

  /**
   * Follows a top-frame navigation the preview canceled with no click or key before it. SWT's Edge
   * with JavaScript off reports no mouse or key event to the {@code Browser}, so on win32 every
   * such navigation is followed; with no script, meta refresh made inert and the content policy,
   * the page starts none by itself. Elsewhere it is dropped.
   *
   * @param location the navigation's location
   * @param follow receives a followed location
   * @return true when followed
   */
  boolean followUngestured(String location, Consumer<String> follow);

  /**
   * Runs a scroll to an anchor on the shown page where {@code Browser.setUrl("about:blank#id")}
   * after {@code setText} scrolls the same page: on GTK. On win32, SWT's Edge shows {@code setText}
   * content in a temporary file's page, so the call would navigate away from it. On cocoa, SWT
   * escapes the {@code #} as {@code %23}, so WebKit loads another page.
   *
   * @param scroll records the anchor and scrolls
   */
  void scrollToAnchor(Runnable scroll);

  /**
   * Runs a check of a top-frame page change where a click on a link to {@code about:blank}, such as
   * {@code href=""} on a {@code setText} page, replaces the page with an empty one without a {@code
   * LocationListener.changing} event to cancel: on GTK, where WebKitGTK asks no navigation policy
   * for {@code about:blank}.
   *
   * @param check notes a page replaced by an empty one
   */
  void pageChanged(Runnable check);

  /**
   * Opens a menu bar menu for Alt and a letter or digit pressed in a browser, on win32. WebView2
   * reports that key as an accelerator, and SWT's Edge sends it to the browser's {@code KeyDown}
   * listeners and never to the shell as {@code WM_SYSCHAR}, so the menu bar mnemonic would open
   * nothing. {@link Win32BrowserBehavior} consumes such a key that no listener consumed first and
   * sends the shell {@code WM_SYSCOMMAND} with {@code SC_KEYMENU} and the key. Elsewhere does
   * nothing.
   *
   * @param browser the browser
   * @param ui posts the menu request, which runs after the key event returns
   */
  void menuMnemonics(Browser browser, UiDispatcher ui);
}
