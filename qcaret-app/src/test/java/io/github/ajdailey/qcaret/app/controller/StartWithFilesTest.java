package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A start that names files while no other instance holds the session, with {@code session.snapshot}
 * on: it restores the session, opens the files after it in the first window and owns the session,
 * so its quit asks nothing and the next start restores its buffers.
 */
class StartWithFilesTest {
  @TempDir Path temp;

  private Path settings;
  private final List<Windows> open = new ArrayList<Windows>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
    Files.writeString(settings.resolve("qcaret.conf"), "session.snapshot=true\n");
  }

  @AfterEach
  void close() throws InterruptedException {
    for (Windows windows : open) {
      if (!windows.views.get(0).disposed) {
        windows.close();
      }
    }
  }

  private Path file(String name) throws IOException {
    return Files.writeString(temp.resolve(name), name + "\n").toRealPath();
  }

  private static StartupRequest named(Path... paths) {
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (Path path : paths) {
      files.add(new FileRequest(path));
    }
    return new StartupRequest(files, null, null, null, null, null, false);
  }

  /** Starts as the launcher does with the session lock free and waits for the tabs to load. */
  private Windows start(StartupRequest request, final int tabs) throws InterruptedException {
    ConfigStore config = new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
    final Windows windows = new Windows(temp, true, config.load());
    open.add(windows);
    SessionStore store = new SessionStore(config.location());
    windows.first().startSession(store, store.load(), request.ownsSession(false), request);
    windows.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !windows.set.session().restoring()
                && windows.first().documents().count() == tabs;
          }
        },
        tabs + " tabs");
    windows.settle("start");
    return windows;
  }

  /** Tabs take their loads' arrival order, so two named files compare as a set. */
  @Test
  void theNamedFilesOpenAfterTheRestoreAndARestoredOneIsSelected() throws Exception {
    Path a = file("a.txt");
    Path b = file("b.txt");
    Path c = file("c.txt");
    List<String> saved = start(named(a, b), 2).first().documents().labels();
    assertEquals(Set.of(a.toString(), b.toString()), Set.copyOf(saved));
    open.get(0).close();

    DocumentController restored = start(named(a), 2).first().documents();
    assertEquals(saved, restored.labels());
    assertEquals(saved.indexOf(a.toString()), restored.activeIndex());
    open.get(1).close();

    DocumentController added = start(named(c), 3).first().documents();
    assertEquals(c.toString(), added.labels().get(2));
    assertEquals(2, added.activeIndex());
  }

  @Test
  void aSessionTabThatDoesNotOpenLeavesTheNamedFilesOpening() throws Exception {
    Path a = file("a.txt");
    Path b = file("b.txt");
    Path c = file("c.txt");
    start(named(a, b), 2).close();
    Files.delete(a);

    Windows after = start(named(c), 2);
    DocumentController documents = after.first().documents();
    assertEquals(List.of(b.toString(), c.toString()), documents.labels());
    assertEquals(1, documents.activeIndex());
  }

  @Test
  void theQuitAsksNothingAndTheNextStartRestoresTheBuffers() throws Exception {
    Path a = file("a.txt");
    final Windows before = start(named(a), 1);
    ApplicationController first = before.first();
    first.documents().activeEditor().type("x");
    first.documents().newDocument();
    first.documents().activeEditor().type("untitled");
    FakeView view = before.view(first);
    first.requestQuit();
    before.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return before.views.get(0).disposed;
          }
        },
        "quit");
    assertNull(view.unsaved, "the quit asked");

    final Windows after = start(named(), 2);
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            DocumentController documents = after.first().documents();
            return documents.documentAt(a) != null && documents.documentAt(a).isDirty();
          }
        },
        "the snapshot buffer");
    DocumentController documents = after.first().documents();
    assertEquals("xa.txt\n", documents.documentAt(a).text().toString());
    documents.select(1);
    assertEquals("untitled", documents.activeDocument().text().toString());
    assertTrue(documents.activeDocument().isDirty());
  }
}
