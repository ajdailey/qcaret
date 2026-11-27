package io.github.ajdailey.qcaret.app.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The hex mode a tab and a remembered file keep, written and read back; absent reads empty. */
class HexModeSessionTest {
  @TempDir Path temp;

  private static Session.Tab tab(String path, String hex) {
    return new Session.Tab(
        path,
        "",
        "",
        "",
        "",
        false,
        4,
        false,
        List.of(new Session.View("0,0", 0, "")),
        0,
        false,
        "",
        "",
        List.<Session.Comment>of(),
        false,
        "",
        hex);
  }

  @Test
  void theHexModeRoundTrips() {
    Session.Window window =
        new Session.Window(
            new WindowState(null, null, null, null, false),
            List.of(tab("/mock/a.bin", "split"), tab("/mock/b.txt", "")),
            0,
            "",
            false,
            "",
            List.<String>of(),
            false,
            "",
            List.<Session.Search>of(),
            List.<String>of(),
            Session.Display.DEFAULTS,
            "",
            new Session.Browse("", "", false));
    Session.FileState file =
        new Session.FileState("/mock/c.bin", "", "", 4, false, "0,0", 0, "", "hex");
    SessionStore store = new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
    assertTrue(store.save(new Session(List.of(window), List.<String>of(), List.of(file))));
    Session read = store.load();
    List<Session.Tab> tabs = read.windows().get(0).tabs();
    assertEquals("split", tabs.get(0).hex());
    assertEquals("", tabs.get(1).hex());
    assertEquals("hex", read.files().get(0).hex());
    assertEquals("hex", read.files().get(0).withPath("/mock/d.bin").hex());
  }
}
