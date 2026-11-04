package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.LoadedFile;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** QLogg APP-1: a load that finishes after its window closed never reaches the window. */
class DocumentOpenerTest {
  private static final Duration BOUND = Duration.ofSeconds(10);

  @TempDir Path temp;

  private ManualUiDispatcher ui;
  private Workers workers;
  private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
  private final List<Object> results = new ArrayList<Object>();
  private DocumentOpener opener;

  @BeforeEach
  void setUp() {
    ui = new ManualUiDispatcher();
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                failure.set(error);
              }
            });
    opener =
        new DocumentOpener(
            workers,
            ui,
            new DocumentOpener.Listener() {
              @Override
              public void opened(Path path, LoadedFile file) {
                ui.checkUiThread();
                results.add(file);
              }

              @Override
              public void failed(Path path, IOException error) {
                ui.checkUiThread();
                results.add(error);
              }
            });
  }

  @AfterEach
  void tearDown() {
    workers.close();
    assertNull(failure.get());
  }

  private BooleanSupplier idle() {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return opener.running() == 0;
      }
    };
  }

  private BooleanSupplier results(final int count) {
    return new BooleanSupplier() {
      @Override
      public boolean getAsBoolean() {
        return results.size() >= count;
      }
    };
  }

  private Path file(String text) throws IOException {
    Path file = temp.resolve("a.txt");
    Files.writeString(file, text);
    return file;
  }

  @Test
  void aLoadReachesTheListenerOnTheUiThread() throws Exception {
    opener.open(file("text\r\n"), LoadOptions.DEFAULTS);
    opener.open(temp.resolve("missing").resolve("b.txt"), LoadOptions.DEFAULTS);
    assertTrue(ui.runUntil(results(2), BOUND));
    int loaded = results.get(0) instanceof LoadedFile ? 0 : 1;
    assertEquals("text\r\n", ((LoadedFile) results.get(loaded)).document().text().toString());
    assertTrue(results.get(1 - loaded) instanceof IOException);
  }

  @Test
  void aLoadHeldPastTheCloseDropsItsDocument() throws Exception {
    GatedCharset gate = new GatedCharset();
    gate.arm();
    opener.open(file("held"), LoadOptions.DEFAULTS.withForced(gate));
    assertTrue(gate.awaitHeld());
    opener.close();
    gate.release();
    assertTrue(Poll.until(idle(), BOUND));
    ui.runPending();
    assertEquals(List.of(), results);
  }

  @Test
  void aResultPostedBeforeTheCloseIsDropped() throws Exception {
    opener.open(file("posted"), LoadOptions.DEFAULTS);
    assertTrue(Poll.until(idle(), BOUND));
    opener.close();
    ui.runPending();
    assertEquals(List.of(), results);
    opener.open(file("after"), LoadOptions.DEFAULTS);
    assertEquals(0, opener.running());
  }
}
