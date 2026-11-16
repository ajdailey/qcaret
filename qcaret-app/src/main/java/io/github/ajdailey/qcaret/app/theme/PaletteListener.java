package io.github.ajdailey.qcaret.app.theme;

/** Receives the palette and the theme setting's light or dark side when they change. */
public interface PaletteListener {
  /**
   * The palette changed; the view recreates its colors from it and repaints.
   *
   * @param palette the new palette
   */
  void paletteChanged(Palette palette);

  /**
   * The theme setting resolved to the other side, which a fixed editor color scheme does not show
   * in the palette.
   *
   * @param dark true when it resolves to dark
   */
  default void themeChanged(boolean dark) {}
}
