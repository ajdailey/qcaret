package io.github.ajdailey.qcaret.ui.swt;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link BrowserPane#same}: the navigation the pane asked for, with its fragment as sent or
 * percent-encoded, and nothing else. cocoa reports the scroll as {@code about:blank%23id} (CI on
 * {@code aa1db2c}), a URL without a fragment.
 */
class BrowserPaneLocationTest {
  @Test
  void theRequestedFragmentMatchesInEitherEncoding() {
    String wanted = "about:blank#caf%C3%A9-1";
    assertTrue(BrowserPane.same("about:blank#caf%C3%A9-1", wanted));
    assertTrue(BrowserPane.same("about:blank#café-1", wanted));
    assertTrue(BrowserPane.same("about:blank#qcaret-line-150", "about:blank#qcaret-line-150"));
    assertTrue(BrowserPane.same("about:blank", "about:blank"));
  }

  @Test
  void anyOtherLocationDoesNotMatch() {
    String wanted = "about:blank#qcaret-line-150";
    assertFalse(BrowserPane.same("about:blank%23qcaret-line-150", wanted), "an escaped #");
    assertFalse(BrowserPane.same("about:blank#qcaret-line-15", wanted));
    assertFalse(BrowserPane.same("about:blank", wanted));
    assertFalse(BrowserPane.same("about:blank#qcaret-line-150", "about:blank"));
    assertFalse(BrowserPane.same("http://127.0.0.1/#qcaret-line-150", wanted));
    assertFalse(BrowserPane.same("about:blank%23x", "about:blank"));
    assertFalse(BrowserPane.same(null, wanted));
  }
}
