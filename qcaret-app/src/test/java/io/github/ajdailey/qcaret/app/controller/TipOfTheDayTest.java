package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.about.TipList;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.config.ConfigStore;
import io.github.ajdailey.qcaret.app.config.Preferences;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.config.TipSettings;
import io.github.ajdailey.qcaret.app.session.Session;
import io.github.ajdailey.qcaret.app.session.SessionStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Help > Tip of the Day: the menu command, Next Tip and the check box through {@link FakeView}, the
 * next tip saved and wrapping, and the startup show after the restore and the recovery offer.
 */
class TipOfTheDayTest {
  @TempDir Path temp;

  private final List<Harness> open = new ArrayList<Harness>();

  @AfterEach
  void close() throws InterruptedException {
    for (Harness harness : open) {
      if (!harness.view.disposed) {
        harness.close();
      }
    }
  }

  private Harness harness() throws IOException {
    Files.createDirectories(temp.resolve("settings"));
    Harness harness = new Harness(temp);
    open.add(harness);
    return harness;
  }

  private SessionStore store() {
    return new SessionStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
  }

  private static void setTips(Harness harness, TipSettings tips) {
    Settings settings = harness.application.settings();
    settings.set(settings.current().withTips(tips));
  }

  private static TipSettings tips(Harness harness) {
    return harness.application.preferences().tips();
  }

  /** Waits until the file holds the settings' tip keys. */
  private void awaitSaved(Harness harness, final TipSettings expected) throws InterruptedException {
    final ConfigStore config =
        new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp.resolve("settings")));
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return config.load().tips().equals(expected);
          }
        },
        "saved " + expected);
  }

  /** Starts without a restore and runs until the recovery offer has ended. */
  private static void startQuiet(final Harness harness, SessionStore store)
      throws InterruptedException {
    harness.application.startSession(store, store.load(), false);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.recovery().idle();
          }
        },
        "the recovery queue ran");
  }

  @Test
  void theMenuShowsTheNextTipAndAdvancesIt() throws Exception {
    Harness harness = harness();
    setTips(harness, TipSettings.DEFAULTS.withNext(2));
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    FakeView.Tips dialog = harness.view.tips;
    String third = TipList.text(2, harness.application.keymap());
    assertEquals(List.of(third), dialog.shown);
    assertFalse(dialog.onStartup);
    assertEquals(3, tips(harness).next());

    dialog.input.nextTip();
    assertEquals(TipList.text(3, harness.application.keymap()), dialog.shown.get(1));
    assertEquals(4, tips(harness).next());
    awaitSaved(harness, TipSettings.DEFAULTS.withNext(4));
  }

  @Test
  void theMenuRaisesAnOpenDialog() throws Exception {
    Harness harness = harness();
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    assertEquals(1, harness.view.tipDialogs);
    assertEquals(1, harness.view.tips.fronted);
    assertEquals(1, harness.view.tips.shown.size());

    harness.view.tips.input.closed();
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    assertEquals(2, harness.view.tipDialogs);
  }

  @Test
  void theNextTipWrapsAfterTheLast() throws Exception {
    Harness harness = harness();
    int last = TipList.size() - 1;
    setTips(harness, TipSettings.DEFAULTS.withNext(last));
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    assertEquals(TipList.text(last, harness.application.keymap()), harness.view.tips.shown.get(0));
    assertEquals(0, tips(harness).next());
    harness.view.tips.input.nextTip();
    assertEquals(TipList.text(0, harness.application.keymap()), harness.view.tips.shown.get(1));
    assertEquals(1, tips(harness).next());
  }

  @Test
  void aSavedIndexPastTheListWraps() throws Exception {
    Harness harness = harness();
    setTips(harness, TipSettings.DEFAULTS.withNext(TipList.size() + 1));
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    assertEquals(TipList.text(1, harness.application.keymap()), harness.view.tips.shown.get(0));
    assertEquals(2, tips(harness).next());
  }

  @Test
  void theCheckBoxWritesTheSetting() throws Exception {
    Harness harness = harness();
    harness.application.execute(CommandId.TIP_OF_THE_DAY);
    harness.view.tips.input.showOnStartup(true);
    assertTrue(tips(harness).onStartup());
    awaitSaved(harness, new TipSettings(true, 1));
    harness.view.tips.input.showOnStartup(false);
    assertFalse(tips(harness).onStartup());
    awaitSaved(harness, new TipSettings(false, 1));
  }

  @Test
  void theStartupShowIsOffByDefault() throws Exception {
    assertFalse(Preferences.DEFAULTS.tips().onStartup());
    Harness harness = harness();
    startQuiet(harness, store());
    harness.awaitIdle("start");
    assertNull(harness.view.tips);
  }

  @Test
  void theStartupShowFollowsTheRestore() throws Exception {
    Path a = Files.writeString(temp.resolve("a.txt"), "alpha\n").toRealPath();
    Harness before = harness();
    SessionStore store = store();
    before.application.startSession(store, store.load(), true);
    before.awaitIdle("first start");
    before.application.documents().closeAll(null);
    before.open(a);
    Session saved = before.application.sessionController().capture(false).session();
    before.close();
    assertTrue(store().save(saved));

    final Harness after = harness();
    setTips(after, new TipSettings(true, 0));
    SessionStore again = store();
    after.application.startSession(again, again.load(), true);
    after.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return after.view.tips != null;
          }
        },
        "the startup tip");
    assertEquals(1, after.view.tabsAtTips);
    assertEquals(List.of(a.toString()), after.application.documents().labels());
    assertTrue(after.view.tips.onStartup);
    assertEquals(1, after.view.tipDialogs);
  }

  @Test
  void theStartupShowWaitsForTheRecoveryDialog() throws Exception {
    Path a = Files.writeString(temp.resolve("a.txt"), "alpha\n").toRealPath();
    Harness dead = harness();
    startQuiet(dead, store());
    dead.open(a);
    dead.application.documents().activeEditor().type("mock ");
    dead.close();

    final Harness next = harness();
    setTips(next, new TipSettings(true, 0));
    SessionStore store = store();
    next.application.startSession(store, store.load(), false);
    next.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return next.view.recovery != null;
          }
        },
        "the recovery dialog");
    next.awaitIdle("offered");
    assertNull(next.view.tips);

    next.view.recovery.later();
    assertTrue(next.view.tips != null);
  }

  @Test
  void theStartupShowWaitsForAModalDialog() throws Exception {
    final Harness harness = harness();
    setTips(harness, new TipSettings(true, 0));
    harness.view.prompt = true;
    startQuiet(harness, store());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.tips().waits() >= 2;
          }
        },
        "a wait for the prompt");
    assertNull(harness.view.tips);

    harness.view.prompt = false;
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.view.tips != null;
          }
        },
        "the tip after the prompt");
  }

  @Test
  void aStartupShowTurnedOffMeanwhileShowsNothing() throws Exception {
    final Harness harness = harness();
    setTips(harness, new TipSettings(true, 0));
    harness.view.prompt = true;
    startQuiet(harness, store());
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return harness.application.tips().waits() >= 1;
          }
        },
        "a wait for the prompt");
    setTips(harness, new TipSettings(false, 0));
    harness.view.prompt = false;
    harness.awaitIdle("idle");
    assertNull(harness.view.tips);
  }
}
