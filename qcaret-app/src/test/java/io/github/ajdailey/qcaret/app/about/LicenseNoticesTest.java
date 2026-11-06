package io.github.ajdailey.qcaret.app.about;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The text Help > About > Licenses shows against what the distributions bundle. */
class LicenseNoticesTest {
  private static String pinned(String pom, String property) {
    Matcher version = Pattern.compile("<" + property + ">([^<]+)<").matcher(pom);
    assertTrue(version.find(), property);
    return version.group(1);
  }

  @Test
  void everyBundledComponentIsNamedWithItsLicenseText() throws IOException {
    String text = AboutInfo.licenseText();
    assertFalse(text.contains(UiText.LICENSES_MISSING.text()), "every file is in the jar");
    for (String expected :
        new String[] {
          "Apache License",
          "Eclipse Standard Widget Toolkit (SWT)",
          "Eclipse Public License - v 2.0",
          "SLF4J: MIT License",
          "Copyright (c) 2004-2023 QOS.ch",
          "Launch4j executable header: MIT License",
          "Copyright (c) 2004, 2015 Grzegorz Kowal",
          "GNU General Public License"
        }) {
      assertTrue(text.contains(expected), expected);
    }
  }

  @Test
  void theNoticesNameTheVersionsThePomPins() throws IOException {
    String text = AboutInfo.licenseText();
    String pom = Files.readString(Path.of("..", "pom.xml"));
    assertTrue(text.contains("(SWT) " + pinned(pom, "swt.version")), "SWT version");
    assertTrue(text.contains("SLF4J " + pinned(pom, "slf4j.version")), "SLF4J version");
  }
}
