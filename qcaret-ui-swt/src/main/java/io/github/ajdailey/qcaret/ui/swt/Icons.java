package io.github.ajdailey.qcaret.ui.swt;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageDataProvider;
import org.eclipse.swt.widgets.Display;

/**
 * The application icon at 16 to 256 px. {@link Variant#DARK} is the OS-facing icon (window,
 * taskbar, Dock); {@link Variant#LIGHT} is for in-app use against a light palette.
 */
public final class Icons {
  private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

  /** Icon artwork. */
  public enum Variant {
    /** The OS-facing icon. */
    DARK("qcaret-"),
    /** For in-app use against the light palette. */
    LIGHT("qcaret-light-");

    final String prefix;

    Variant(String prefix) {
      this.prefix = prefix;
    }
  }

  private Icons() {}

  /**
   * Loads every size of a variant. The caller owns and disposes the images.
   *
   * @param display the Display
   * @param variant the artwork
   * @return the images, smallest first; a size whose resource is missing or unreadable is skipped
   */
  public static List<Image> load(Display display, Variant variant) {
    List<Image> images = new ArrayList<Image>();
    for (int size : SIZES) {
      String name = "icons/" + variant.prefix + size + ".png";
      try (InputStream in = Icons.class.getResourceAsStream(name)) {
        if (in != null) {
          images.add(new Image(display, in));
        }
      } catch (IOException | SWTException e) {
        // A missing size leaves the window system to scale another.
        continue;
      }
    }
    return images;
  }

  /**
   * Loads a variant at a logical size: the PNG of that size at 100% zoom and of twice the size at
   * 200%; SWT scales one of them for other zoom levels. The caller owns and disposes the image.
   *
   * @param display the Display
   * @param variant the artwork
   * @param size logical pixels; a size in the set whose double is also in it, such as 128
   * @return the image, or null when the PNG is missing or unreadable
   */
  public static Image logo(Display display, final Variant variant, final int size) {
    ImageDataProvider provider =
        new ImageDataProvider() {
          @Override
          public ImageData getImageData(int zoom) {
            int pixels = zoom == 200 ? 2 * size : zoom == 100 ? size : 0;
            return pixels == 0 ? null : data(variant, pixels);
          }
        };
    if (data(variant, size) == null) {
      return null;
    }
    return new Image(display, provider);
  }

  private static ImageData data(Variant variant, int pixels) {
    try (InputStream in =
        Icons.class.getResourceAsStream("icons/" + variant.prefix + pixels + ".png")) {
      return in == null ? null : new ImageData(in);
    } catch (IOException | SWTException e) {
      return null;
    }
  }
}
