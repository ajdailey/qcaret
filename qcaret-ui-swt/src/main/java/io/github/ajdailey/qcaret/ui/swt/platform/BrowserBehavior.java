package io.github.ajdailey.qcaret.ui.swt.platform;

import java.util.function.Consumer;

/**
 * Embedded browser differences, one implementation per platform, from {@link
 * PlatformTraits#browserBehavior}: whether a {@code Browser} may be created, and how the Markdown
 * preview treats links, anchors and a replaced page. Call on the UI thread.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Browser</th><th>Ungestured link</th>
 *       <th>Anchor scroll</th><th>Blank page check</th></tr>
 *   <tr><td>win32</td><td>{@link Win32BrowserBehavior}</td><td>yes</td><td>followed</td><td>no</td>
 *       <td>no</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaBrowserBehavior}</td><td>yes</td><td>dropped</td><td>no</td>
 *       <td>no</td></tr>
 *   <tr><td>GTK 3</td><td>{@link Gtk3BrowserBehavior}</td><td>yes</td><td>dropped</td><td>yes</td>
 *       <td>yes</td></tr>
 *   <tr><td>GTK 4</td><td>{@link Gtk4BrowserBehavior}</td><td>no</td><td>dropped</td><td>yes</td>
 *       <td>yes</td></tr>
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
}
