package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** View > Always on Top: the toggle, an unsupported platform and the session round trip. */
class AlwaysOnTopTest {
  @TempDir Path temp;

  private Path settings;
  private final List<Harness> open = new ArrayList<Harness>();

  @BeforeEach
  void create() throws IOException {
    settings = Files.createDirectories(temp.resolve("settings"));
  }

  @AfterEach
  void close() throws InterruptedException {
    for (Harness harness : open) {
      if (!harness.view.disposed) {
        harness.close();
      }
    }
  }

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, settings));
  }

  /** Starts an application over the saved session, its view supported or not. */
  private Harness start(boolean supported) throws InterruptedException {
    final Harness harness = new Harness(temp);
    open.add(harness);
    harness.view.onTopSupported = supported;
    SessionStore store = store();
    harness.application.startSession(store, store.load(), true);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return !harness.application.sessionController().restoring();
          }
        },
        "the restore ended");
    harness.awaitIdle("restore");
    return harness;
  }

  @Test
  void theToggleSetsTheViewEachWay() throws Exception {
    Harness harness = start(true);
    CommandStates states = new CommandStates(harness.application);
    assertTrue(states.enabled(CommandId.ALWAYS_ON_TOP));
    assertFalse(states.checked(CommandId.ALWAYS_ON_TOP));
    assertEquals(List.of(), harness.view.onTopCalls);
    harness.application.execute(CommandId.ALWAYS_ON_TOP);
    assertTrue(states.checked(CommandId.ALWAYS_ON_TOP));
    harness.application.execute(CommandId.ALWAYS_ON_TOP);
    assertFalse(states.checked(CommandId.ALWAYS_ON_TOP));
    assertEquals(List.of(true, false), harness.view.onTopCalls);
  }

  @Test
  void anUnsupportedPlatformDisablesTheItem() throws Exception {
    Harness harness = start(false);
    CommandStates states = new CommandStates(harness.application);
    assertFalse(states.enabled(CommandId.ALWAYS_ON_TOP));
    harness.application.execute(CommandId.ALWAYS_ON_TOP);
    assertFalse(states.checked(CommandId.ALWAYS_ON_TOP));
    assertEquals(List.of(), harness.view.onTopCalls);
  }

  @Test
  void theStateIsSavedPerWindowAndRestoredWithIt() throws Exception {
    Harness first = start(true);
    first.application.execute(CommandId.ALWAYS_ON_TOP);
    first.ui.runPending();
    first.close();
    assertTrue(store().load().windows().get(0).onTop());

    Harness second = start(true);
    assertTrue(second.application.alwaysOnTop().shown());
    assertEquals(List.of(true), second.view.onTopCalls);
    second.application.execute(CommandId.ALWAYS_ON_TOP);
    second.close();
    assertFalse(store().load().windows().get(0).onTop());
  }

  /** A start where the platform is unsupported keeps the saved flag for the next start. */
  @Test
  void anUnsupportedStartKeepsTheSavedFlag() throws Exception {
    Harness first = start(true);
    first.application.execute(CommandId.ALWAYS_ON_TOP);
    first.close();

    Harness second = start(false);
    assertFalse(second.application.alwaysOnTop().shown());
    assertEquals(List.of(), second.view.onTopCalls);
    second.close();
    assertTrue(store().load().windows().get(0).onTop());
  }
}
