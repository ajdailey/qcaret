package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The charsets the Encoding menu lists: {@link TextEncoding#MENU} first, then every other charset
 * the runtime supports. The runtime's charset set is fixed, so {@link #available()} computes the
 * lists once. Listing every charset loads each provider (130 to 229 ms cold, measured in QLogg), so
 * {@link #available()} runs on a worker; {@link #commonCharsets()} looks up the common names only.
 *
 * @param common the supported entries of {@link TextEncoding#MENU}, in that order
 * @param other every other supported charset, in {@link Charset#availableCharsets()} order
 */
public record EncodingChoices(List<Charset> common, List<Charset> other) {
  /**
   * Copies the lists.
   *
   * @param common the common charsets
   * @param other the other charsets
   */
  public EncodingChoices {
    common = List.copyOf(common);
    other = List.copyOf(other);
  }

  /** Receives the choices on the UI thread. */
  public interface Listener {
    /**
     * Called once the choices are computed.
     *
     * @param choices the runtime's choices
     */
    void loaded(EncodingChoices choices);
  }

  /** The runtime's choices once a call to {@link #available()} has computed them. */
  private static volatile EncodingChoices loaded;

  /**
   * Returns the runtime's choices, computing them on the first call.
   *
   * @return the choices
   */
  @WorkerThread
  public static EncodingChoices available() {
    EncodingChoices choices = loaded;
    if (choices == null) {
      choices = of(new ArrayList<Charset>(Charset.availableCharsets().values()));
      loaded = choices;
    }
    return choices;
  }

  /**
   * Returns the supported entries of {@link TextEncoding#MENU}, looked up by name.
   *
   * @return the common charsets, in that order
   */
  public static List<Charset> commonCharsets() {
    List<Charset> common = new ArrayList<Charset>();
    for (String name : TextEncoding.MENU) {
      Charset charset = lookup(name);
      if (charset != null) {
        common.add(charset);
      }
    }
    return common;
  }

  /**
   * Splits charsets into the common ones and the rest.
   *
   * @param all every charset to list
   * @return the choices
   */
  static EncodingChoices of(List<Charset> all) {
    List<Charset> common = new ArrayList<Charset>();
    for (Charset charset : commonCharsets()) {
      if (all.contains(charset)) {
        common.add(charset);
      }
    }
    List<Charset> other = new ArrayList<Charset>();
    for (Charset charset : all) {
      if (!common.contains(charset)) {
        other.add(charset);
      }
    }
    return new EncodingChoices(common, other);
  }

  private static Charset lookup(String name) {
    try {
      return Charset.isSupported(name) ? Charset.forName(name) : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
