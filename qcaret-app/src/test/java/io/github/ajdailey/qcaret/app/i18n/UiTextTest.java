package io.github.ajdailey.qcaret.app.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every {@link UiText} constant has a bundle entry and every entry has a constant. */
class UiTextTest {
  @Test
  void constantsAndBundleKeysMatch() {
    List<String> constants = new ArrayList<String>();
    for (UiText text : UiText.values()) {
      constants.add(text.name());
      assertFalse(text.text().isEmpty(), text.name());
    }
    List<String> keys = new ArrayList<String>();
    Enumeration<String> names = UiText.bundle().getKeys();
    while (names.hasMoreElements()) {
      keys.add(names.nextElement());
    }
    Collections.sort(constants);
    Collections.sort(keys);
    assertEquals(constants, keys);
  }

  @Test
  void patternsFormat() {
    assertEquals("QCaret 0.1.0", UiText.ABOUT_NAME.format("0.1.0"));
    assertEquals("==== NOTICE ====", UiText.LICENSES_HEADING.format("NOTICE"));
    assertEquals("Settings: /mock (portable)", UiText.DETAILS_SETTINGS.format("/mock", "portable"));
  }
}
