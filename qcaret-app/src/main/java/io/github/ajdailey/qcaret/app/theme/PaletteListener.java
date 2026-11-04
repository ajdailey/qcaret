package io.github.ajdailey.qcaret.app.theme;

/** Receives the palette when it changes. */
public interface PaletteListener {
  /**
   * The palette changed; the view recreates its colors from it and repaints.
   *
   * @param palette the new palette
   */
  void paletteChanged(Palette palette);
}
