package io.github.ajdailey.qcaret.app.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code ui.theme} and {@code ui.editorColors} to a {@link Palette} and pushes a change to
 * the views. {@link ThemeMode#SYSTEM} follows {@link ThemeService#systemDark} and its change
 * events. Each mode change, and each system change in {@link ThemeMode#SYSTEM}, also sets {@link
 * ThemeService#preferDark} from {@code ui.theme} alone, so native controls follow the theme while
 * {@link EditorSchemes} picks the palette; the startup setting is the launcher's. {@link
 * #setColors} lays a theme file's colors over the palette of every mode. Confined to the UI thread.
 */
public final class ThemeController {
  private final List<PaletteListener> listeners = new ArrayList<PaletteListener>();
  private final ThemeService service;
  private ThemeMode mode;
  private String editorColors;
  private boolean systemDark;
  private Palette palette;
  private ThemeColors colors = ThemeColors.NONE;

  /**
   * Creates the controller.
   *
   * @param service the system setting
   * @param mode the setting at startup
   * @param editorColors the {@link EditorSchemes} name at startup
   */
  public ThemeController(ThemeService service, ThemeMode mode, String editorColors) {
    this.service = service;
    this.mode = mode;
    this.editorColors = editorColors;
    this.systemDark = service.systemDark();
    this.palette = EditorSchemes.palette(editorColors, mode, systemDark);
    service.addListener(
        new ThemeListener() {
          @Override
          public void systemThemeChanged(boolean dark) {
            systemDark = dark;
            if (ThemeController.this.mode == ThemeMode.SYSTEM) {
              ThemeController.this.service.preferDark(dark);
            }
            apply(colors.over(builtIn()));
          }
        });
  }

  /**
   * Returns the palette of a mode.
   *
   * @param mode the setting
   * @param systemDark the system setting
   * @return {@link Palette#DARK} for dark, and for system while the system is dark; else {@link
   *     Palette#LIGHT}
   */
  public static Palette resolve(ThemeMode mode, boolean systemDark) {
    return switch (mode) {
      case LIGHT -> Palette.LIGHT;
      case DARK -> Palette.DARK;
      case SYSTEM -> systemDark ? Palette.DARK : Palette.LIGHT;
    };
  }

  /**
   * Returns the setting.
   *
   * @return the mode
   */
  public ThemeMode mode() {
    return mode;
  }

  /**
   * Returns the resolved palette.
   *
   * @return the palette
   */
  public Palette palette() {
    return palette;
  }

  /**
   * Changes the setting and pushes the palette when it changes.
   *
   * @param next the mode
   * @return true when native controls show the mode now; false when they show it from the next
   *     start
   */
  public boolean setMode(ThemeMode next) {
    mode = next;
    boolean dark = resolve(next, systemDark).dark();
    service.preferDark(dark);
    apply(colors.over(builtIn()));
    return service.showsNow(dark);
  }

  /**
   * Returns the editor color scheme.
   *
   * @return the {@link EditorSchemes} name
   */
  public String editorColors() {
    return editorColors;
  }

  /**
   * Changes the editor color scheme and pushes the palette when it changes; native controls keep
   * theirs.
   *
   * @param next the {@link EditorSchemes} name
   */
  public void setEditorColors(String next) {
    editorColors = next;
    apply(colors.over(builtIn()));
  }

  /**
   * Replaces the theme file's colors and pushes the palette when it changes.
   *
   * @param next the colors
   */
  public void setColors(ThemeColors next) {
    colors = next;
    apply(next.over(builtIn()));
  }

  /**
   * Adds a listener.
   *
   * @param listener called on the UI thread
   */
  public void addListener(PaletteListener listener) {
    listeners.add(listener);
  }

  /**
   * Removes a listener.
   *
   * @param listener a listener added earlier
   */
  public void removeListener(PaletteListener listener) {
    listeners.remove(listener);
  }

  private Palette builtIn() {
    return EditorSchemes.palette(editorColors, mode, systemDark);
  }

  private void apply(Palette next) {
    if (next.equals(palette)) {
      return;
    }
    palette = next;
    for (PaletteListener listener : new ArrayList<PaletteListener>(listeners)) {
      listener.paletteChanged(next);
    }
  }
}
