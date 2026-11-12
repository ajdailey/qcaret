package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** {@code help.tipsOnStartup} and {@code help.nextTip} in {@code qcaret.conf}. */
class TipSettingsTest {
  @TempDir Path temp;

  private ConfigStore store() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  @Test
  void theDefaultsAreOffAtTheFirstTip() {
    assertEquals(new TipSettings(false, 0), Preferences.DEFAULTS.tips());
    assertEquals(TipSettings.DEFAULTS, store().load().tips());
  }

  @Test
  void savedSettingsReadBack() throws Exception {
    Preferences on = Preferences.DEFAULTS.withTips(new TipSettings(true, 7));
    assertTrue(store().save(Preferences.DEFAULTS, on));
    assertEquals(new TipSettings(true, 7), store().load().tips());
    String text = Files.readString(temp.resolve("qcaret.conf"));
    assertTrue(text.contains("help.tipsOnStartup=true"), text);
    assertTrue(text.contains("help.nextTip=7"), text);
  }

  @Test
  void invalidValuesFallBackToTheDefaults() throws Exception {
    Files.writeString(
        temp.resolve("qcaret.conf"), "version=1\nhelp.tipsOnStartup=yes\nhelp.nextTip=-1\n");
    assertEquals(TipSettings.DEFAULTS, store().load().tips());
    Files.writeString(temp.resolve("qcaret.conf"), "version=1\nhelp.nextTip=10000\n");
    assertEquals(TipSettings.DEFAULTS, store().load().tips());
  }

  @Test
  void theIndexIsChecked() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            TipSettings.DEFAULTS.withNext(TipSettings.MAX_NEXT + 1);
          }
        });
  }
}
