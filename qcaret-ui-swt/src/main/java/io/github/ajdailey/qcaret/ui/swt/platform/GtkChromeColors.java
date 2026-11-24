package io.github.ajdailey.qcaret.ui.swt.platform;

import org.eclipse.swt.widgets.Control;

/**
 * Chrome colors on GTK: the native theme draws the main window's surfaces, fields and trees dark
 * and light, so nothing is colored. GTK's disabled effect brightens an image on a dark background,
 * so every icon set fades its disabled tool items whenever it takes the light ink.
 */
final class GtkChromeColors implements ChromeColors {
  @Override
  public void add(Control surface) {}

  @Override
  public void add(Icons set) {
    set.fadeDisabled(true);
  }

  @Override
  public void apply(boolean dark) {}

  @Override
  public void dispose() {}
}
