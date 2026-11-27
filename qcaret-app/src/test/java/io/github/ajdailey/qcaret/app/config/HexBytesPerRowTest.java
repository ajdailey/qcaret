package io.github.ajdailey.qcaret.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** {@code hex.bytesPerRow}: 16 by default, 8, 16 or 32 read and written, anything else refused. */
class HexBytesPerRowTest {
  @TempDir Path temp;

  private ConfigStore store() {
    return new ConfigStore(new SettingsLocation(SettingsMode.EXPLICIT, temp));
  }

  private Path conf() {
    return temp.resolve("qcaret.conf");
  }

  @Test
  void theKeyDefaultsTo16ReadsWritesAndFallsBack() throws IOException {
    assertEquals(16, Options.DEFAULTS.hexBytesPerRow());
    assertEquals(16, store().load().options().hexBytesPerRow());
    Files.writeString(conf(), "hex.bytesPerRow= 32 \n");
    assertEquals(32, store().load().options().hexBytesPerRow());
    Files.writeString(conf(), "hex.bytesPerRow=12\n");
    assertEquals(16, store().load().options().hexBytesPerRow());
    Files.writeString(conf(), "hex.bytesPerRow=eight\n");
    assertEquals(16, store().load().options().hexBytesPerRow());
    Files.delete(conf());
    Preferences eight = Preferences.DEFAULTS.withOptions(Options.DEFAULTS.withHexBytesPerRow(8));
    assertTrue(store().save(Preferences.DEFAULTS, eight));
    assertTrue(
        Files.readAllLines(conf(), StandardCharsets.ISO_8859_1).contains("hex.bytesPerRow=8"));
    assertEquals(eight, store().load());
  }

  @Test
  void otherWidthsAreRefusedAndOtherCopiesKeepTheWidth() {
    assertThrows(
        IllegalArgumentException.class,
        new Executable() {
          @Override
          public void execute() {
            Options.DEFAULTS.withHexBytesPerRow(24);
          }
        });
    Options eight = Options.DEFAULTS.withHexBytesPerRow(8);
    assertEquals(8, eight.withPreviewZoom(150).hexBytesPerRow());
    assertEquals(8, eight.withMarkdownOpenMode(MarkdownOpenMode.SOURCE).hexBytesPerRow());
  }
}
