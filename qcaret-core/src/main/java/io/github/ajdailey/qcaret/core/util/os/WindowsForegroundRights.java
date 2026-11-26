package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Windows: user32 {@code AllowSetForegroundWindow(ASFW_ANY)} through {@code java.lang.foreign}. The
 * API is a preview in Java 21, so every name is resolved by reflection, as in {@code Flock}, and
 * linked at the first call.
 */
final class WindowsForegroundRights implements ForegroundRights {
  static final WindowsForegroundRights INSTANCE = new WindowsForegroundRights();

  /** The package of the API on every JDK since 21. */
  static final String FOREIGN = "java.lang.foreign.";

  /** Win32 {@code ASFW_ANY}, {@code (DWORD) -1}. */
  static final int ASFW_ANY = -1;

  private MethodHandle allow;

  private WindowsForegroundRights() {}

  @Override
  public synchronized void grantAny() throws IOException {
    Object granted;
    try {
      if (allow == null) {
        allow = link(FOREIGN);
      }
      granted =
          MethodHandle.class
              .getMethod("invokeWithArguments", Object[].class)
              .invoke(allow, (Object) new Object[] {ASFW_ANY});
    } catch (ReflectiveOperationException
        | ClassCastException
        | IllegalArgumentException
        | LinkageError e) {
      Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
      throw new IOException(
          "AllowSetForegroundWindow is not available on Java " + Runtime.version() + ": " + cause,
          cause);
    }
    if (Integer.valueOf(0).equals(granted)) {
      throw new IOException(
          "AllowSetForegroundWindow failed: this process holds no foreground right");
    }
  }

  /**
   * Links user32 {@code AllowSetForegroundWindow}, taking and returning an {@code int}.
   *
   * @param foreign the prefix of the API's class names, normally {@link #FOREIGN}
   * @return the call
   * @throws ReflectiveOperationException when a name is missing or differs, user32 or the symbol
   *     does not resolve, or native access is denied
   */
  static MethodHandle link(String foreign) throws ReflectiveOperationException {
    Class<?> linkerClass = Class.forName(foreign + "Linker");
    Class<?> lookup = Class.forName(foreign + "SymbolLookup");
    Class<?> arena = Class.forName(foreign + "Arena");
    Class<?> segment = Class.forName(foreign + "MemorySegment");
    Class<?> descriptor = Class.forName(foreign + "FunctionDescriptor");
    Class<?> layout = Class.forName(foreign + "MemoryLayout");
    Class<?> option = Class.forName(foreign + "Linker$Option");
    Object javaInt = Class.forName(foreign + "ValueLayout").getField("JAVA_INT").get(null);
    Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
    Object user32 =
        lookup
            .getMethod("libraryLookup", String.class, arena)
            .invoke(null, "user32", arena.getMethod("global").invoke(null));
    Optional<?> symbol =
        (Optional<?>)
            lookup.getMethod("find", String.class).invoke(user32, "AllowSetForegroundWindow");
    if (symbol.isEmpty()) {
      throw new NoSuchMethodException("AllowSetForegroundWindow");
    }
    Object parameters = Array.newInstance(layout, 1);
    Array.set(parameters, 0, javaInt);
    Method of = descriptor.getMethod("of", layout, parameters.getClass());
    Object options = Array.newInstance(option, 0);
    return (MethodHandle)
        linkerClass
            .getMethod("downcallHandle", segment, descriptor, options.getClass())
            .invoke(linker, symbol.get(), of.invoke(null, javaInt, parameters), options);
  }
}
