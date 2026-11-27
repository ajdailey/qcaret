package io.github.ajdailey.qcaret.app.i18n;

import java.util.Enumeration;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * The {@code en-XA} pseudo bundle: every key of the English {@code messages.properties}, its value
 * passed through {@link PseudoLocale#translate}. {@link ResourceBundle#getBundle} finds this class
 * by name for {@link PseudoLocale#LOCALE}; it is on the test class path only.
 */
public final class messages_en_XA extends ResourceBundle {
  private final ResourceBundle english =
      ResourceBundle.getBundle(
          "io.github.ajdailey.qcaret.app.i18n.messages",
          Locale.ROOT,
          messages_en_XA.class.getClassLoader(),
          ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));

  /** Creates the bundle over the English values. */
  public messages_en_XA() {
    // ResourceBundle.getBundle instantiates it.
  }

  @Override
  protected Object handleGetObject(String key) {
    return english.containsKey(key) ? PseudoLocale.translate(english.getString(key)) : null;
  }

  @Override
  protected Set<String> handleKeySet() {
    return english.keySet();
  }

  @Override
  public Enumeration<String> getKeys() {
    return english.getKeys();
  }
}
