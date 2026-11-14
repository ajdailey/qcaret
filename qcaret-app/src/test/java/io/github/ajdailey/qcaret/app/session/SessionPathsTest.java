package io.github.ajdailey.qcaret.app.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.WindowState;
import io.github.ajdailey.qcaret.app.search.SearchRequest;
import io.github.ajdailey.qcaret.app.view.SearchScope;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The session's paths through {@link io.github.ajdailey.qcaret.app.config.InstallPaths}. */
class SessionPathsTest {
  @TempDir Path temp;

  private Path install;
  private Path outside;

  @BeforeEach
  void layout() throws IOException {
    install = Files.createDirectories(temp.resolve("install"));
    Files.createDirectories(install.resolve("logs").resolve("old"));
    Files.writeString(install.resolve("logs").resolve("a.log"), "a");
    outside = Files.writeString(temp.resolve("outside.txt"), "o");
  }

  /** Every path key, each naming {@code root/logs} or below, plus one file outside. */
  private Session session(Path root) {
    Path logs = root.resolve("logs");
    String a = logs.resolve("a.log").toString();
    Session.Tab tab =
        new Session.Tab(
            a, "", "", "", "", false, 4, false, List.of(), 0, false, "", "", List.of(), false);
    Session.Tab away =
        new Session.Tab(
            outside.toString(),
            "",
            "",
            "",
            "",
            false,
            4,
            false,
            List.of(),
            0,
            false,
            "",
            "",
            List.of(),
            false);
    SearchRequest request =
        new SearchRequest("x", false, false, false, SearchScope.TREE, logs, "", "", "", false);
    Session.Window window =
        new Session.Window(
            WindowState.UNKNOWN,
            List.of(tab, away),
            0,
            root.toString(),
            true,
            "",
            List.of(logs.toString(), logs.resolve("old").toString()),
            false,
            "",
            List.of(new Session.Search(request, false)),
            List.of(logs.resolve("closed.log").toString()),
            Session.Display.DEFAULTS,
            "",
            new Session.Browse(logs.toString(), "", false),
            false);
    return new Session(
        List.of(window),
        List.of(a, outside.toString()),
        List.of(new Session.FileState(a, "UTF-8", "", 2, true, "1,1", 0)));
  }

  private static Properties keys(Path file) throws IOException {
    Properties p = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.ISO_8859_1)) {
      p.load(in);
    }
    return p;
  }

  @Test
  void aMovedPortableInstallKeepsEveryPath() throws IOException {
    SessionStore store =
        new SessionStore(new SettingsLocation(SettingsMode.PORTABLE, install, install));
    assertEquals(true, store.save(session(install)));
    Properties written = keys(install.resolve(SessionStore.FILE));
    assertEquals("logs/a.log", written.getProperty("window.0.tab.0.path"));
    assertEquals(outside.toString(), written.getProperty("window.0.tab.1.path"));
    assertEquals("logs/a.log", written.getProperty("file.0.path"));
    assertEquals("logs/a.log", written.getProperty("recent.0"));
    assertEquals(outside.toString(), written.getProperty("recent.1"));
    assertEquals("logs/closed.log", written.getProperty("window.0.closed.0"));
    assertEquals(".", written.getProperty("window.0.workspace"));
    assertEquals("logs", written.getProperty("window.0.browseFolder"));
    assertEquals("logs/old", written.getProperty("window.0.expanded.1"));
    assertEquals("logs", written.getProperty("window.0.search.0.folder"));

    Path moved = Files.move(install, temp.resolve("moved"));
    Session loaded =
        new SessionStore(new SettingsLocation(SettingsMode.PORTABLE, moved, moved)).load();
    assertEquals(session(moved.toRealPath()), loaded);
  }

  @Test
  void perUserModeWritesAbsolutePaths() throws IOException {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    SessionStore store =
        new SessionStore(new SettingsLocation(SettingsMode.PER_USER, settings, install));
    Session session = session(install);
    store.save(session);
    Properties written = keys(settings.resolve(SessionStore.FILE));
    assertEquals(
        install.resolve("logs").resolve("a.log").toString(),
        written.getProperty("window.0.tab.0.path"));
    assertEquals(install.toString(), written.getProperty("window.0.workspace"));
    assertEquals(session, store.load());
  }

  @Test
  void aRelativeAndAnAbsoluteKeyForOneFileMeet() throws IOException {
    Path settings = Files.createDirectories(temp.resolve("settings"));
    String real = install.toRealPath().resolve("logs").resolve("a.log").toString();
    Files.writeString(
        settings.resolve(SessionStore.FILE),
        "file.0.path=logs/a.log\nfile.1.path="
            + real.replace("\\", "\\\\")
            + "\nrecent.0=logs/a.log\n",
        StandardCharsets.ISO_8859_1);
    Session loaded =
        new SessionStore(new SettingsLocation(SettingsMode.PER_USER, settings, install)).load();
    assertEquals(real, loaded.files().get(0).path());
    assertEquals(real, loaded.files().get(1).path());
    assertEquals(List.of(real), loaded.recent());
  }
}
