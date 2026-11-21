package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;

/**
 * Reads a GTK 3 widget's ATK name as AT-SPI does: {@code g_object_get} of {@code accessible-name}
 * calls {@code atk_object_get_name}, which runs the accessible's own {@code get_name}. GTK 3 only.
 */
public final class AtkNames {
  private static final byte[] PROPERTY = "accessible-name\0".getBytes(StandardCharsets.US_ASCII);

  private AtkNames() {}

  /**
   * Returns the name of a tool item's push button.
   *
   * @param item the item
   * @return the name, or null when it has none
   * @throws ReflectiveOperationException when an SWT internal does not resolve
   */
  public static String of(ToolItem item) throws ReflectiveOperationException {
    long button =
        (Long) method("gtk3.GTK3", "gtk_bin_get_child", long.class).invoke(null, handle(item));
    return read(button);
  }

  /**
   * Returns the name of a control's widget.
   *
   * @param control the control
   * @return the name, or null when it has none
   * @throws ReflectiveOperationException when an SWT internal does not resolve
   */
  public static String of(Control control) throws ReflectiveOperationException {
    return read(handle(control));
  }

  private static long handle(Widget widget) throws ReflectiveOperationException {
    return Widget.class.getField("handle").getLong(widget);
  }

  private static String read(long widget) throws ReflectiveOperationException {
    long atk =
        (Long) method("gtk3.GTK3", "gtk_widget_get_accessible", long.class).invoke(null, widget);
    long[] out = new long[1];
    method("gtk.OS", "g_object_get", long.class, byte[].class, long[].class, long.class)
        .invoke(null, atk, PROPERTY, out, 0L);
    if (out[0] == 0) {
      return null;
    }
    try {
      int length = (Integer) method("C", "strlen", long.class).invoke(null, out[0]);
      byte[] bytes = new byte[length];
      method("C", "memmove", byte[].class, long.class, long.class)
          .invoke(null, bytes, out[0], (long) length);
      return new String(bytes, StandardCharsets.UTF_8);
    } finally {
      method("gtk.OS", "g_free", long.class).invoke(null, out[0]);
    }
  }

  private static Method method(String type, String name, Class<?>... parameters)
      throws ReflectiveOperationException {
    return Class.forName("org.eclipse.swt.internal." + type, false, Widget.class.getClassLoader())
        .getMethod(name, parameters);
  }
}
