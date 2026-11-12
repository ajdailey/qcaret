package io.github.ajdailey.qcaret.app.about;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AboutInfoTest {
  @TempDir Path temp;

  @Test
  void theOsLineIsNameVersionAndArchitecture() {
    AboutInfo info =
        AboutInfo.collect("mock toolkit", new SettingsLocation(SettingsMode.EXPLICIT, temp));
    assertEquals(
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"),
        info.os());
  }
}
