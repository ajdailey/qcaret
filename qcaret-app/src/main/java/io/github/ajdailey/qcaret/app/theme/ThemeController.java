package io.github.ajdailey.qcaret.app.theme;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves {@code ui.theme} to a {@link Palette} and pushes a change to the views. {@link
 * ThemeMode#SYSTEM} follows {@link ThemeService#systemDark} and its change events. Each mode
 * change, and each system change in {@link ThemeMode#SYSTEM}, also sets {@link
 * ThemeService#preferDark}, so native controls match the palette; the startup setting is the
 * launcher's. Confined to the UI thread.
 */
public final class ThemeController {
  private final List<PaletteListener> listeners = new ArrayList<PaletteListener>();
  private final ThemeService service;
  private ThemeMode mode;
  private boolean systemDark;
  private Palette palette;

  /**
   * Creates the controller.
   *
   * @param service the system setting
   * @param mode the setting at startup
   */
  public ThemeController(ThemeService service, ThemeMode mode) {
    this.service = service;
    this.mode = mode;
    this.systemDark = service.systemDark();
    this.palette = resolve(mode, systemDark);
    service.addListener(
        new ThemeListener() {
          @Override
          public void systemThemeChanged(boolean dark) {
            systemDark = dark;
            if (ThemeController.this.mode == ThemeMode.SYSTEM) {
              ThemeController.this.service.preferDark(dark);
            }
            apply(resolve(ThemeController.this.mode, dark));
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
    Palette resolved = resolve(next, systemDark);
    service.preferDark(resolved.dark());
    apply(resolved);
    return service.showsNow(resolved.dark());
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

  private void apply(Palette next) {
    if (next == palette) {
      return;
    }
    palette = next;
    for (PaletteListener listener : new ArrayList<PaletteListener>(listeners)) {
      listener.paletteChanged(next);
    }
  }
}
