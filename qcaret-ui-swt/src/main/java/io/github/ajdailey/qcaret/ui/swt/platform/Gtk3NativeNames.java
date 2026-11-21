package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names on GTK 3: sets the {@code accessible-name} property of the widget's ATK object, through
 * SWT's {@code Widget.handle}, {@code GTK3.gtk_widget_get_accessible} and {@code OS.g_object_set}.
 * A tool item's name goes on the GtkButton inside its GtkToolButton ({@code
 * GTK3.gtk_bin_get_child}), the object AT-SPI reports as the push button; GTK's button accessible
 * reads that name before its label. A member that does not resolve, or a call that fails, logs one
 * WARN and leaves later calls doing nothing.
 */
final class Gtk3NativeNames implements NativeNames {
  private static final Logger LOG = LoggerFactory.getLogger(Gtk3NativeNames.class);

  /** The ATK object property, NUL-terminated. */
  private static final byte[] PROPERTY = "accessible-name\0".getBytes(StandardCharsets.US_ASCII);

  private final Field handle;
  private final Method accessible;
  private final Method child;
  private final Method set;
  private boolean supported = true;

  /** Resolves the members; a failure leaves the names unsupported. */
  Gtk3NativeNames() {
    Field field = null;
    Method getAccessible = null;
    Method binChild = null;
    Method objectSet = null;
    try {
      field = Widget.class.getField("handle");
      ClassLoader loader = Widget.class.getClassLoader();
      Class<?> gtk3 = Class.forName("org.eclipse.swt.internal.gtk3.GTK3", false, loader);
      getAccessible = gtk3.getMethod("gtk_widget_get_accessible", long.class);
      binChild = gtk3.getMethod("gtk_bin_get_child", long.class);
      objectSet =
          Class.forName("org.eclipse.swt.internal.gtk.OS", false, loader)
              .getMethod("g_object_set", long.class, byte[].class, byte[].class, long.class);
    } catch (ReflectiveOperationException | SecurityException | InaccessibleObjectException e) {
      fail(e);
    }
    handle = field;
    accessible = getAccessible;
    child = binChild;
    set = objectSet;
  }

  /**
   * Reports whether the members resolved and no call has failed.
   *
   * @return true when names are set
   */
  boolean supported() {
    return supported;
  }

  @Override
  public void name(ToolItem item, String name) {
    if (!supported || item.isDisposed()) {
      return;
    }
    try {
      long button = (Long) child.invoke(null, handle.getLong(item));
      setName(button, name);
    } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
      fail(e);
    }
  }

  @Override
  public void name(Control control, String name) {
    if (!supported || control.isDisposed()) {
      return;
    }
    try {
      setName(handle.getLong(control), name);
    } catch (ReflectiveOperationException | IllegalArgumentException | LinkageError e) {
      fail(e);
    }
  }

  private void setName(long widget, String name) throws ReflectiveOperationException {
    if (widget == 0) {
      return;
    }
    long atk = (Long) accessible.invoke(null, widget);
    if (atk != 0) {
      byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
      byte[] value = new byte[utf8.length + 1];
      System.arraycopy(utf8, 0, value, 0, utf8.length);
      set.invoke(null, atk, PROPERTY, value, 0L);
    }
  }

  private void fail(Throwable cause) {
    if (supported) {
      supported = false;
      LOG.warn("Accessible names are off: a reflective call into SWT internals failed", cause);
    }
  }
}
