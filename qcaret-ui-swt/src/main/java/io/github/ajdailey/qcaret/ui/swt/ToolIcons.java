package io.github.ajdailey.qcaret.ui.swt;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The toolbar and toggle icons, rendered at build time from the SVG set into {@code
 * icons/tool/NAME-light-16.png} (dark ink) and {@code NAME-dark-16.png} (light ink), each with a 32
 * px copy for 200% zoom. The ink follows the native widget background, not the QCaret palette,
 * because toolbars and buttons are native. Images are created on first use on the UI thread and
 * disposed with the owner control.
 */
final class ToolIcons {
  /** Opens files. */
  static final String OPEN = "open";

  /** Reloads the file. */
  static final String RELOAD = "reload";

  /** Stops loading or searching. */
  static final String STOP = "stop";

  /** Match case. */
  static final String CASE = "case";

  /** Regular expression. */
  static final String REGEX = "regex";

  /** Whole word. */
  static final String WORD = "word";

  /** Search in the selection. */
  static final String SELECTION = "selection";

  /** Clears the search text. */
  static final String CLEAR = "clear";

  /** Runs the search. */
  static final String SEARCH = "search";

  /** Adds a row. */
  static final String ADD = "add";

  /** Removes a row. */
  static final String REMOVE = "remove";

  /** Moves a row up. */
  static final String UP = "up";

  /** Moves a row down. */
  static final String DOWN = "down";

  /** A checked box. */
  static final String CHECKED = "checked";

  /** An empty box. */
  static final String UNCHECKED = "unchecked";

  /** Background luminance, 0-255, below which the light ink is used. */
  private static final int DARK_BACKGROUND = 128;

  private final Display display;
  private final Control owner;
  private final Map<String, Image> images = new HashMap<String, Image>();
  private final Map<ToolItem, String> items = new LinkedHashMap<ToolItem, String>();
  private boolean darkBackground;

  /**
   * Creates the set for controls with the owner's background; the images are disposed with it.
   *
   * @param owner a control whose background the icons are drawn on
   */
  ToolIcons(Control owner) {
    this.display = owner.getDisplay();
    this.owner = owner;
    this.darkBackground = dark(owner.getBackground());
    final Listener settings =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            reink();
          }
        };
    display.addListener(SWT.Settings, settings);
    owner.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            display.removeListener(SWT.Settings, settings);
            dispose();
          }
        });
  }

  /**
   * Shows an icon on a tool item and keeps it in the owner's ink across {@link #reink}.
   *
   * @param item the item
   * @param name one of the name constants
   * @return false when the PNG is missing from the jar and the item shows no image
   */
  boolean apply(ToolItem item, String name) {
    Image image = get(name);
    if (image == null) {
      return false;
    }
    item.setImage(image);
    items.put(item, name);
    return true;
  }

  /**
   * Reads the owner's background again and, when it calls for the other ink, replaces the image of
   * every item given to {@link #apply}. Native controls change background with the theme.
   */
  void reink() {
    if (owner.isDisposed()) {
      return;
    }
    boolean now = dark(owner.getBackground());
    if (now == darkBackground) {
      return;
    }
    darkBackground = now;
    List<Image> old = new ArrayList<Image>(images.values());
    images.clear();
    for (Map.Entry<ToolItem, String> entry : items.entrySet()) {
      if (!entry.getKey().isDisposed()) {
        entry.getKey().setImage(get(entry.getValue()));
      }
    }
    for (Image image : old) {
      if (image != null) {
        image.dispose();
      }
    }
  }

  /**
   * Reports whether a background calls for the light ink.
   *
   * @param background the background color
   * @return true when its luminance is below the midpoint
   */
  static boolean dark(Color background) {
    int luminance =
        (background.getRed() * 299 + background.getGreen() * 587 + background.getBlue() * 114)
            / 1000;
    return luminance < DARK_BACKGROUND;
  }

  /**
   * Returns an icon, created on first use.
   *
   * @param name one of the name constants
   * @return the image, or null when its PNG is missing from the jar
   */
  Image get(String name) {
    Image image = images.get(name);
    if (image == null && !images.containsKey(name)) {
      image = create(name);
      images.put(name, image);
    }
    return image;
  }

  /**
   * Reports which ink the icons use.
   *
   * @return true for the light ink drawn on a dark background
   */
  boolean darkBackground() {
    return darkBackground;
  }

  private Image create(String name) {
    final String prefix = "icons/tool/" + name + (darkBackground ? "-dark-" : "-light-");
    if (data(prefix + 16) == null) {
      return null;
    }
    return new Image(
        display,
        new ImageDataProvider() {
          @Override
          public ImageData getImageData(int zoom) {
            return zoom == 100 ? data(prefix + 16) : zoom == 200 ? data(prefix + 32) : null;
          }
        });
  }

  private static ImageData data(String resource) {
    try (InputStream in = ToolIcons.class.getResourceAsStream(resource + ".png")) {
      return in == null ? null : new ImageData(in);
    } catch (IOException | SWTException e) {
      return null;
    }
  }

  private void dispose() {
    for (Image image : images.values()) {
      if (image != null) {
        image.dispose();
      }
    }
    images.clear();
  }
}
