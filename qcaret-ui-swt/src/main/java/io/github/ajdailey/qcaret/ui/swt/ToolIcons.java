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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ToolItem;

/**
 * The toolbar, toggle and menu icons, rendered at build time from the SVG set into {@code
 * icons/tool/NAME-light-16.png} (dark ink) and {@code NAME-dark-16.png} (light ink), each with a 32
 * px copy for 200% zoom. The ink follows the native widget background, not the QCaret palette,
 * because toolbars and buttons are native. GTK refreshes {@code getBackground} and sends {@code
 * SWT.Settings} only on the first {@code Display.sleep} after {@code setDarkThemePreferred}, so on
 * GTK a visible owner is read from a drawn pixel. GTK's disabled effect lightens an image, which on
 * a dark background brightens it, so there {@link #showState} shows a disabled item a faded copy.
 * SWT's disabled copy of a GTK menu image brightens the same way and keeps the alpha, so the same
 * faded copy serves menu items. Images are created on first use on the UI thread and disposed with
 * the owner control.
 */
final class ToolIcons {
  /** Opens files. */
  static final String OPEN = "open";

  /** Opens an untitled document. */
  static final String NEW = "new";

  /** Saves the document. */
  static final String SAVE = "save";

  /** Saves every document. */
  static final String SAVE_ALL = "save-all";

  /** Undoes the last edit. */
  static final String UNDO = "undo";

  /** Redoes the last undone edit. */
  static final String REDO = "redo";

  /** Cuts the selection. */
  static final String CUT = "cut";

  /** Copies the selection. */
  static final String COPY = "copy";

  /** Pastes at the selection. */
  static final String PASTE = "paste";

  /** Opens the find bar's replace row. */
  static final String REPLACE = "replace";

  /** Opens HyperSearch. */
  static final String HYPERSEARCH = "hypersearch";

  /** Enlarges the editor font. */
  static final String ZOOM_IN = "zoom-in";

  /** Shrinks the editor font. */
  static final String ZOOM_OUT = "zoom-out";

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

  /** Prints the document. */
  static final String PRINT = "print";

  /** Saves the document to a chosen file. */
  static final String SAVE_AS = "save-as";

  /** Closes the document. */
  static final String CLOSE = "close";

  /** Quits the application. */
  static final String QUIT = "quit";

  /** Selects the whole document. */
  static final String SELECT_ALL = "select-all";

  /** Opens Preferences. */
  static final String PREFERENCES = "preferences";

  /** Selects the next match. */
  static final String FIND_NEXT = "find-next";

  /** Selects the previous match. */
  static final String FIND_PREVIOUS = "find-previous";

  /** Moves the caret to a line. */
  static final String GO_TO_LINE = "go-to-line";

  /** Opens the user guide. */
  static final String HELP = "help";

  /** Opens About. */
  static final String ABOUT = "about";

  /** Background luminance, 0-255, below which the light ink is used. */
  private static final int DARK_BACKGROUND = 128;

  /** Side of the image a visible owner is drawn into; the corner holds no item. */
  private static final int SAMPLE = 2;

  /** Suffix of a faded copy's key in the image cache. */
  private static final String FADED = "#faded";

  /** Percent of its opacity a faded copy keeps. */
  private static final int FADE_PERCENT = 40;

  /** Side of an icon at 100% zoom. */
  private static final int SIDE = 16;

  private static final boolean GTK = "gtk".equals(SWT.getPlatform());

  private final Display display;
  private final Control owner;
  private final Map<String, Image> images = new HashMap<String, Image>();
  private final Map<ToolItem, String> items = new LinkedHashMap<ToolItem, String>();
  private final Map<MenuItem, String> menuItems = new LinkedHashMap<MenuItem, String>();
  private final boolean fixedInk;
  private Image blank;
  private boolean darkBackground;

  /**
   * Creates the set for controls with the owner's background; the images are disposed with it.
   *
   * @param owner a control whose background the icons are drawn on
   */
  ToolIcons(Control owner) {
    this(owner, false, dark(owner.getBackground()));
  }

  /**
   * Creates the set for a background no control shows and no theme change reaches; the images are
   * disposed with the owner.
   *
   * @param owner the control that disposes the images
   * @param dark true for the light ink
   */
  ToolIcons(Control owner, boolean dark) {
    this(owner, true, dark);
  }

  private ToolIcons(Control owner, boolean fixedInk, boolean dark) {
    this.display = owner.getDisplay();
    this.owner = owner;
    this.fixedInk = fixedInk;
    this.darkBackground = dark;
    final Listener settings =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            reink();
          }
        };
    display.addListener(SWT.Settings, settings);
    owner.addListener(SWT.Settings, settings);
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
   * Shows an icon on a menu item and keeps it in the owner's ink across {@link #reink} until the
   * item is disposed.
   *
   * @param item the item
   * @param name one of the name constants
   * @return false when the PNG is missing from the jar and the item shows no image
   */
  boolean apply(final MenuItem item, String name) {
    if (get(name) == null) {
      return false;
    }
    menuItems.put(item, name);
    item.addListener(
        SWT.Dispose,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            menuItems.remove(item);
          }
        });
    showState(item);
    return true;
  }

  /**
   * Returns a transparent image of an icon's size, created on first use.
   *
   * @return the image, disposed with the owner
   */
  Image blank() {
    if (blank == null) {
      blank =
          new Image(
              display,
              new ImageDataProvider() {
                @Override
                public ImageData getImageData(int zoom) {
                  return zoom == 100
                      ? transparent(SIDE)
                      : zoom == 200 ? transparent(2 * SIDE) : null;
                }
              });
    }
    return blank;
  }

  /**
   * Reads the owner's background again and, when it calls for the other ink, replaces the image of
   * every item given to {@link #apply}. Native controls change background with the theme. A set of
   * fixed ink stays as it is.
   */
  void reink() {
    if (fixedInk || owner.isDisposed()) {
      return;
    }
    boolean now = dark(background());
    if (now == darkBackground) {
      return;
    }
    darkBackground = now;
    List<Image> old = new ArrayList<Image>(images.values());
    images.clear();
    for (ToolItem item : items.keySet()) {
      showState(item);
    }
    for (MenuItem item : menuItems.keySet()) {
      showState(item);
    }
    for (Image image : old) {
      if (image != null) {
        image.dispose();
      }
    }
  }

  /**
   * Shows the image of an item's enabled state; call after changing it. An item not given to {@link
   * #apply} keeps its image.
   *
   * @param item the item
   */
  void showState(ToolItem item) {
    String name = items.get(item);
    if (name == null || item.isDisposed()) {
      return;
    }
    Image image = get(GTK && darkBackground && !item.getEnabled() ? name + FADED : name);
    if (item.getImage() != image) {
      item.setImage(image);
    }
  }

  /**
   * Shows the image of a menu item's enabled state; call after changing it. An item not given to
   * {@link #apply} keeps its image.
   *
   * @param item the item
   */
  void showState(MenuItem item) {
    String name = menuItems.get(item);
    if (name == null || item.isDisposed()) {
      return;
    }
    Image image = get(GTK && darkBackground && !item.getEnabled() ? name + FADED : name);
    if (item.getImage() != image) {
      item.setImage(image);
    }
  }

  /** Returns the color the owner shows now. */
  private Color background() {
    if (!GTK || !owner.isVisible()) {
      return owner.getBackground();
    }
    Image image = new Image(display, SAMPLE, SAMPLE);
    GC gc = new GC(image);
    try {
      if (!owner.print(gc)) {
        return owner.getBackground();
      }
      ImageData data = image.getImageData();
      RGB rgb = data.palette.getRGB(data.getPixel(SAMPLE - 1, SAMPLE - 1));
      return new Color(rgb);
    } finally {
      gc.dispose();
      image.dispose();
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

  private Image create(String key) {
    final boolean faded = key.endsWith(FADED);
    String name = faded ? key.substring(0, key.length() - FADED.length()) : key;
    final String prefix = "icons/tool/" + name + (darkBackground ? "-dark-" : "-light-");
    if (data(prefix + 16) == null) {
      return null;
    }
    return new Image(
        display,
        new ImageDataProvider() {
          @Override
          public ImageData getImageData(int zoom) {
            ImageData data =
                zoom == 100 ? data(prefix + 16) : zoom == 200 ? data(prefix + 32) : null;
            return faded && data != null ? fade(data) : data;
          }
        });
  }

  private static ImageData transparent(int side) {
    ImageData data = new ImageData(side, side, 32, new PaletteData(0xff0000, 0xff00, 0xff));
    data.alphaData = new byte[side * side];
    return data;
  }

  private static ImageData fade(ImageData data) {
    byte[] alpha = data.alphaData;
    if (alpha != null) {
      for (int i = 0; i < alpha.length; i++) {
        alpha[i] = (byte) ((alpha[i] & 0xff) * FADE_PERCENT / 100);
      }
    }
    return data;
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
    if (blank != null) {
      blank.dispose();
    }
  }
}
