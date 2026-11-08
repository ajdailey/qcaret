package io.github.ajdailey.qcaret.app.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link UiText} constants match the bundle keys, and patterns and counts format. */
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

  @Test
  void countsTakeTheSingularForOne() {
    assertEquals("1 match", UiText.count(1, UiText.COUNT_MATCH, UiText.COUNT_MATCHES));
    assertEquals("2 matches", UiText.count(2, UiText.COUNT_MATCH, UiText.COUNT_MATCHES));
    assertEquals("0 files", UiText.count(0, UiText.COUNT_FILE, UiText.COUNT_FILES));
    assertEquals("1 file", UiText.count(1, UiText.COUNT_FILE, UiText.COUNT_FILES));
    assertEquals("2 files", UiText.count(2, UiText.COUNT_FILE, UiText.COUNT_FILES));
    assertEquals("1,200 symbols", UiText.count(1200, UiText.COUNT_SYMBOL, UiText.COUNT_SYMBOLS));
    assertEquals(
        "\"LIMIT\" in Workspace folder /mock: 1 match in 1 file",
        UiText.SEARCH_SET.format(
            "LIMIT",
            "Workspace folder /mock",
            UiText.count(1, UiText.COUNT_MATCH, UiText.COUNT_MATCHES),
            UiText.count(1, UiText.COUNT_FILE, UiText.COUNT_FILES)));
    assertEquals(
        "Replaces 1 of 2 matches in 1 of 1 file. Files not open are backed up for Restore Replace"
            + " Run.",
        UiText.REPLACE_SUMMARY.format(
            1,
            UiText.count(2, UiText.COUNT_MATCH, UiText.COUNT_MATCHES),
            1,
            UiText.count(1, UiText.COUNT_FILE, UiText.COUNT_FILES)));
  }
}
