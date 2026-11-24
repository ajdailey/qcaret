package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import org.eclipse.swt.widgets.ToolItem;

/**
 * Tool item sizing on GTK 4: a tool item's GtkPicture takes the icon's size as its minimum through
 * SWT internals, since the box GTK 4 SWT wraps it in measures 0 high under GTK 4.14. Without them
 * the item stays blank.
 */
final class Gtk4ToolItemSizing implements ToolItemSizing {
  /** SWT's {@code ToolItem.imageHandle}, the item's GtkPicture; null when it does not resolve. */
  private static final Field PICTURE;

  /** SWT's internal {@code GTK4.gtk_picture_set_can_shrink}; null when it does not resolve. */
  private static final Method CAN_SHRINK;

  static {
    Field picture = null;
    Method canShrink = null;
    try {
      picture = ToolItem.class.getDeclaredField("imageHandle");
      picture.setAccessible(true);
      canShrink =
          Class.forName("org.eclipse.swt.internal.gtk4.GTK4")
              .getMethod("gtk_picture_set_can_shrink", long.class, boolean.class);
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      picture = null;
      canShrink = null;
    }
    PICTURE = picture;
    CAN_SHRINK = canShrink;
  }

  /**
   * Reports whether the SWT internals resolved.
   *
   * @return true when items can keep their icon's size
   */
  static boolean resolved() {
    return CAN_SHRINK != null;
  }

  @Override
  public void keepIconSize(ToolItem item) {
    if (CAN_SHRINK == null) {
      return;
    }
    try {
      long picture = PICTURE.getLong(item);
      if (picture != 0) {
        CAN_SHRINK.invoke(null, picture, false);
      }
    } catch (ReflectiveOperationException | IllegalArgumentException e) {
      // The item keeps SWT's layout.
    }
  }
}
