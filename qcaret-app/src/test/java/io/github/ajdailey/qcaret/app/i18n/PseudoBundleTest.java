package io.github.ajdailey.qcaret.app.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

/**
 * The {@code en-XA} pseudo bundle: found for its locale, the base bundle's keys, and every value
 * translated with its specifiers and mnemonic intact.
 */
class PseudoBundleTest {
  private static final String BUNDLE = "io.github.ajdailey.qcaret.app.i18n.messages";

  private static ResourceBundle bundle(Locale locale) {
    return ResourceBundle.getBundle(
        BUNDLE,
        locale,
        UiText.class.getClassLoader(),
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT));
  }

  private static List<String> keys(ResourceBundle bundle) {
    List<String> keys = new ArrayList<String>();
    Enumeration<String> names = bundle.getKeys();
    while (names.hasMoreElements()) {
      keys.add(names.nextElement());
    }
    Collections.sort(keys);
    return keys;
  }

  @Test
  void theLocaleFindsThePseudoBundleWithTheBaseKeys() {
    ResourceBundle pseudo = bundle(PseudoLocale.LOCALE);
    assertEquals(PseudoLocale.LOCALE, pseudo.getLocale());
    assertEquals(keys(bundle(Locale.ROOT)), keys(pseudo));
  }

  @Test
  void everyValueIsTranslatedWithItsSpecifiersAndMnemonic() {
    ResourceBundle english = bundle(Locale.ROOT);
    ResourceBundle pseudo = bundle(PseudoLocale.LOCALE);
    for (String key : keys(english)) {
      String base = english.getString(key);
      String shown = pseudo.getString(key);
      assertNotEquals(base, shown, key);
      assertEquals(PseudoLocale.OPEN, shown.charAt(0), key);
      String close = shown.endsWith("...") ? shown.substring(0, shown.length() - 3) : shown;
      assertEquals(PseudoLocale.CLOSE, close.charAt(close.length() - 1), key);
      assertTrue(shown.length() >= base.length() * 1.3, key + ": " + shown);
      assertEquals(PseudoLocale.specifiers(base), PseudoLocale.specifiers(shown), key);
      assertEquals(PseudoLocale.mnemonic(base), PseudoLocale.mnemonic(shown), key);
    }
  }

  @Test
  void translationKeepsSpecifiersMnemonicsAndTheEllipsis() {
    assertEquals("[\u00d6\u00fe&e\u00f1 \u013c]...", PseudoLocale.translate("Op&en..."));
    assertEquals("[ļîñé %d, %1$s 100%% ļöŕ]", PseudoLocale.translate("line %d, %1$s 100%%"));
    assertEquals("[Å && Ɓ ļ]", PseudoLocale.translate("A && B"));
  }
}
