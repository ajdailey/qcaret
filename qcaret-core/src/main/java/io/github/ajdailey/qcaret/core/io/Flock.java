package io.github.ajdailey.qcaret.core.io;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The C calls {@code open}, {@code flock} and {@code close} for {@link ProcessLock} on Linux and
 * macOS, through {@code java.lang.foreign}. The API is a preview in Java 21, so a class file that
 * names it needs {@code --enable-preview}, which {@code java -jar} cannot be given; every name here
 * is resolved by reflection and checked only at run time. A name missing or changed on the running
 * JDK, or native access denied, fails {@link #bind} with an {@link IOException}.
 *
 * <p>Each call allocates its {@code errno} capture in its own arena: a shared one lets another
 * thread's successful call overwrite a failure's {@code errno}.
 */
final class Flock {
  /** The package of the API on every JDK since 21. */
  static final String FOREIGN = "java.lang.foreign.";

  /** The result of {@link #acquire} when another open file description holds the lock. */
  static final int HELD = -1;

  private static final int LOCK_EX_NB = 2 | 4;
  private static final int MODE = 0600;

  /**
   * One system's constants.
   *
   * @param open {@code O_RDWR | O_CREAT | O_CLOEXEC}
   * @param wouldBlock {@code EWOULDBLOCK}
   */
  record Constants(int open, int wouldBlock) {
    static final Constants LINUX = new Constants(2 | 0x40 | 0x80000, 11);
    static final Constants MACOS = new Constants(2 | 0x200 | 0x1000000, 35);

    /**
     * Returns the constants of a system.
     *
     * @param osName the {@code os.name} property
     * @return the constants
     * @throws IOException when the system is neither Linux nor macOS
     */
    static Constants of(String osName) throws IOException {
      if (osName.startsWith("Linux")) {
        return LINUX;
      }
      if (osName.startsWith("Mac")) {
        return MACOS;
      }
      throw new IOException("No file lock for " + osName);
    }
  }

  private final Constants constants;
  private final Charset pathCharset;
  private final Method invoke;
  private final Object open;
  private final Object flock;
  private final Object close;
  private final Method ofConfined;
  private final Method closeArena;
  private final Method allocateLayout;
  private final Method allocateBytes;
  private final Method asByteBuffer;
  private final Method getInt;
  private final Object javaInt;
  private final Object captureLayout;
  private final long errnoOffset;

  private Flock(String foreign, Constants constants) throws ReflectiveOperationException {
    this.constants = constants;
    pathCharset = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
    invoke = MethodHandle.class.getMethod("invokeWithArguments", Object[].class);
    Class<?> linkerClass = Class.forName(foreign + "Linker");
    Object linker = linkerClass.getMethod("nativeLinker").invoke(null);
    Class<?> option = Class.forName(foreign + "Linker$Option");
    Class<?> descriptor = Class.forName(foreign + "FunctionDescriptor");
    Class<?> layout = Class.forName(foreign + "MemoryLayout");
    Class<?> pathElement = Class.forName(foreign + "MemoryLayout$PathElement");
    Class<?> segment = Class.forName(foreign + "MemorySegment");
    Class<?> arena = Class.forName(foreign + "Arena");
    Class<?> valueLayout = Class.forName(foreign + "ValueLayout");
    Class<?> ofInt = Class.forName(foreign + "ValueLayout$OfInt");
    Class<?> lookupClass = Class.forName(foreign + "SymbolLookup");

    javaInt = valueLayout.getField("JAVA_INT").get(null);
    Object address = valueLayout.getField("ADDRESS").get(null);
    captureLayout = option.getMethod("captureStateLayout").invoke(null);
    Object errnoElement = pathElement.getMethod("groupElement", String.class).invoke(null, "errno");
    errnoOffset =
        (long)
            layout
                .getMethod("byteOffset", arrayOf(pathElement, 0).getClass())
                .invoke(captureLayout, (Object) arrayOf(pathElement, 1, errnoElement));
    Object capture =
        option
            .getMethod("captureCallState", String[].class)
            .invoke(null, (Object) new String[] {"errno"});
    Object variadic = option.getMethod("firstVariadicArg", int.class).invoke(null, 2);

    Object lookup = linkerClass.getMethod("defaultLookup").invoke(linker);
    Method find = lookupClass.getMethod("find", String.class);
    Method describe = descriptor.getMethod("of", layout, arrayOf(layout, 0).getClass());
    Method downcall =
        linkerClass.getMethod("downcallHandle", segment, descriptor, arrayOf(option, 0).getClass());
    open =
        downcall.invoke(
            linker,
            symbol(find, lookup, "open"),
            describe.invoke(null, javaInt, arrayOf(layout, 3, address, javaInt, javaInt)),
            arrayOf(option, 2, capture, variadic));
    flock =
        downcall.invoke(
            linker,
            symbol(find, lookup, "flock"),
            describe.invoke(null, javaInt, arrayOf(layout, 2, javaInt, javaInt)),
            arrayOf(option, 1, capture));
    close =
        downcall.invoke(
            linker,
            symbol(find, lookup, "close"),
            describe.invoke(null, javaInt, arrayOf(layout, 1, javaInt)),
            arrayOf(option, 0));

    ofConfined = arena.getMethod("ofConfined");
    closeArena = arena.getMethod("close");
    allocateLayout = arena.getMethod("allocate", layout);
    allocateBytes = arena.getMethod("allocate", long.class, long.class);
    asByteBuffer = segment.getMethod("asByteBuffer");
    getInt = segment.getMethod("get", ofInt, long.class);
  }

  /**
   * Links the calls.
   *
   * @param foreign the prefix of the API's class names, normally {@link #FOREIGN}
   * @param constants the system's constants
   * @return the calls
   * @throws IOException when the running JDK lacks a name, a name differs or native access is
   *     denied
   */
  static Flock bind(String foreign, Constants constants) throws IOException {
    try {
      return new Flock(foreign, constants);
    } catch (ReflectiveOperationException
        | ClassCastException
        | IllegalArgumentException
        | LinkageError e) {
      throw unavailable(e);
    }
  }

  /**
   * Opens a file, creating it owner-only, and takes an exclusive {@code flock} without waiting. The
   * descriptor is close-on-exec, so no child process keeps the lock after this one dies.
   *
   * @param file the file
   * @return the descriptor, holding the lock, or {@link #HELD}
   * @throws IOException when a call fails
   */
  int acquire(Path file) throws IOException {
    byte[] name = file.toString().getBytes(pathCharset);
    try {
      Object arena = ofConfined.invoke(null);
      try {
        Object capture = allocateLayout.invoke(arena, captureLayout);
        Object path = allocateBytes.invoke(arena, name.length + 1L, 1L);
        ((ByteBuffer) asByteBuffer.invoke(path)).put(name).put((byte) 0);
        int descriptor = call(open, capture, path, constants.open(), MODE);
        if (descriptor < 0) {
          throw new IOException("open of " + file + " failed: errno " + errno(capture));
        }
        return lock(descriptor, capture, file);
      } finally {
        closeArena.invoke(arena);
      }
    } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
      throw unavailable(e);
    }
  }

  /** Locks an open descriptor; it is closed unless this returns it. */
  private int lock(int descriptor, Object capture, Path file)
      throws ReflectiveOperationException, IOException {
    boolean locked = false;
    try {
      locked = call(flock, capture, descriptor, LOCK_EX_NB) == 0;
      if (locked) {
        return descriptor;
      }
      int errno = errno(capture);
      if (errno == constants.wouldBlock()) {
        return HELD;
      }
      throw new IOException("flock of " + file + " failed: errno " + errno);
    } finally {
      if (!locked) {
        call(close, descriptor);
      }
    }
  }

  /**
   * Closes a descriptor, which releases its lock.
   *
   * @param descriptor a descriptor from {@link #acquire}
   * @throws IOException when the call fails
   */
  void release(int descriptor) throws IOException {
    try {
      if (call(close, descriptor) != 0) {
        throw new IOException("close of a lock file failed");
      }
    } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
      throw unavailable(e);
    }
  }

  private int call(Object handle, Object... arguments) throws ReflectiveOperationException {
    return (int) invoke.invoke(handle, (Object) arguments);
  }

  private int errno(Object capture) throws ReflectiveOperationException {
    return (int) getInt.invoke(capture, javaInt, errnoOffset);
  }

  private static Object symbol(Method find, Object lookup, String name)
      throws ReflectiveOperationException {
    Optional<?> found = (Optional<?>) find.invoke(lookup, name);
    if (found.isEmpty()) {
      throw new NoSuchMethodException(name);
    }
    return found.get();
  }

  /** Makes an array of a class only known at run time. */
  private static Object arrayOf(Class<?> type, int length, Object... elements) {
    Object array = Array.newInstance(type, length);
    for (int i = 0; i < elements.length; i++) {
      Array.set(array, i, elements[i]);
    }
    return array;
  }

  private static IOException unavailable(Throwable failure) {
    Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
    return new IOException(
        "flock is not available on Java " + Runtime.version() + ": " + cause, cause);
  }
}
