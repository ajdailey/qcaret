package io.github.ajdailey.qcaret.ui.swt.platform;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.eclipse.swt.widgets.Display;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pop-up menu theme on win32. SWT's {@code setDarkThemePreferred} calls only {@code
 * SetPreferredAppMode}, and uxtheme keeps drawing menus in the side they were first drawn in until
 * {@code FlushMenuThemes}, uxtheme ordinal 136, which has no exported name. {@link #flush} calls it
 * through {@code java.lang.foreign}, a preview API in Java 21, so every name is resolved by
 * reflection, as in {@code Flock}. A name missing on the running JDK or Windows, or native access
 * denied, logs one WARN, and menus keep the side the Display started with.
 */
final class Win32MenuThemes {
  /** The package of the foreign-function API on every JDK since 21. */
  static final String FOREIGN = "java.lang.foreign.";

  /** The uxtheme ordinal of {@code FlushMenuThemes}. */
  static final long FLUSH_MENU_THEMES = 136;

  private static final Logger LOG = LoggerFactory.getLogger(Win32MenuThemes.class);

  /** Display data key: the side {@link #flush} last gave the menus. */
  private static final String SHOWN_DARK = Win32MenuThemes.class.getName() + ".shownDark";

  /** The linked call, the failure that stopped it, or null before the first {@link #flush}. */
  private static Object linked;

  private final Object linker;
  private final Class<?> layout;
  private final Class<?> option;
  private final Object address;
  private final Method libraryLookup;
  private final Method global;
  private final Method find;
  private final Method describe;
  private final Method describeVoid;
  private final Method downcallHandle;
  private final Method ofConfined;
  private final Method closeArena;
  private final Method allocate;
  private final Method asByteBuffer;
  private final Method ofAddress;
  private final Method addressOf;
  private final Method invoke;
  private MethodHandle flush;

  /**
   * Resolves the API's names; no native call runs.
   *
   * @param foreign the prefix of the API's class names, normally {@link #FOREIGN}
   * @throws ReflectiveOperationException when a name is missing or differs
   */
  Win32MenuThemes(String foreign) throws ReflectiveOperationException {
    Class<?> linkerClass = Class.forName(foreign + "Linker");
    Class<?> lookup = Class.forName(foreign + "SymbolLookup");
    Class<?> arena = Class.forName(foreign + "Arena");
    Class<?> segment = Class.forName(foreign + "MemorySegment");
    Class<?> descriptor = Class.forName(foreign + "FunctionDescriptor");
    layout = Class.forName(foreign + "MemoryLayout");
    option = Class.forName(foreign + "Linker$Option");
    linker = linkerClass.getMethod("nativeLinker").invoke(null);
    address = Class.forName(foreign + "ValueLayout").getField("ADDRESS").get(null);
    libraryLookup = lookup.getMethod("libraryLookup", String.class, arena);
    global = arena.getMethod("global");
    find = lookup.getMethod("find", String.class);
    describe = descriptor.getMethod("of", layout, Array.newInstance(layout, 0).getClass());
    describeVoid = descriptor.getMethod("ofVoid", Array.newInstance(layout, 0).getClass());
    downcallHandle =
        linkerClass.getMethod(
            "downcallHandle", segment, descriptor, Array.newInstance(option, 0).getClass());
    ofConfined = arena.getMethod("ofConfined");
    closeArena = arena.getMethod("close");
    allocate = arena.getMethod("allocate", long.class, long.class);
    asByteBuffer = segment.getMethod("asByteBuffer");
    ofAddress = segment.getMethod("ofAddress", long.class);
    addressOf = segment.getMethod("address");
    invoke = MethodHandle.class.getMethod("invokeWithArguments", Object[].class);
  }

  /**
   * Makes pop-up menus drawn from now on show the native side {@link NativeTheme#set} gave. Call on
   * the UI thread after {@code NativeTheme.set}.
   *
   * @param display the Display
   * @param dark the side set
   * @return true when the menus follow it
   */
  static boolean flush(Display display, boolean dark) {
    Win32MenuThemes themes = linked();
    if (themes == null) {
      return false;
    }
    try {
      themes.call(themes.flush);
    } catch (ReflectiveOperationException | ClassCastException | IllegalArgumentException e) {
      failed(e);
      return false;
    }
    shown(display, dark);
    return true;
  }

  /**
   * Records the side pop-up menus show after a flush.
   *
   * @param display the Display
   * @param dark true for dark
   */
  static void shown(Display display, boolean dark) {
    display.setData(SHOWN_DARK, dark);
  }

  /**
   * Reports the side pop-up menus show: the side of the last {@link #flush}, else the side the
   * Display started with.
   *
   * @param display the Display
   * @return true for dark
   */
  static boolean shownDark(Display display) {
    Object shown = display.getData(SHOWN_DARK);
    return shown == null ? NativeTheme.startDark(display) : Boolean.TRUE.equals(shown);
  }

  /** Returns the linked call, linking it once; null when linking or a call failed. */
  private static synchronized Win32MenuThemes linked() {
    if (linked == null) {
      try {
        Win32MenuThemes themes = new Win32MenuThemes(FOREIGN);
        themes.link();
        linked = themes;
      } catch (ReflectiveOperationException
          | ClassCastException
          | IllegalArgumentException
          | IllegalStateException
          | LinkageError e) {
        failed(e);
      }
    }
    return linked instanceof Win32MenuThemes themes ? themes : null;
  }

  /** Stops further calls, with one WARN. */
  private static synchronized void failed(Throwable failure) {
    Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
    linked = cause;
    LOG.warn("Menus keep the theme QCaret started with: uxtheme FlushMenuThemes failed", cause);
  }

  /**
   * Links {@code FlushMenuThemes}: {@code LoadLibraryA("uxtheme.dll")}, then {@code GetProcAddress}
   * by ordinal.
   *
   * @return the address of {@code FlushMenuThemes}
   * @throws ReflectiveOperationException when a call fails or native access is denied
   * @throws IllegalStateException when uxtheme or the ordinal does not resolve
   */
  long link() throws ReflectiveOperationException {
    Object kernel32 = libraryLookup.invoke(null, "kernel32", global.invoke(null));
    MethodHandle load = downcall(symbol(kernel32, "LoadLibraryA"), true, 1);
    MethodHandle proc = downcall(symbol(kernel32, "GetProcAddress"), true, 2);
    Object arena = ofConfined.invoke(null);
    long module;
    try {
      module = address(call(load, ascii(arena, "uxtheme.dll")));
    } finally {
      closeArena.invoke(arena);
    }
    if (module == 0) {
      throw new IllegalStateException("LoadLibraryA(uxtheme.dll) failed");
    }
    Object function =
        call(proc, ofAddress.invoke(null, module), ofAddress.invoke(null, FLUSH_MENU_THEMES));
    long at = address(function);
    if (at == 0) {
      throw new IllegalStateException("uxtheme has no ordinal " + FLUSH_MENU_THEMES);
    }
    flush = downcall(function, false, 0);
    return at;
  }

  private Object symbol(Object library, String name) throws ReflectiveOperationException {
    Optional<?> found = (Optional<?>) find.invoke(library, name);
    if (found.isEmpty()) {
      throw new NoSuchMethodException(name);
    }
    return found.get();
  }

  /** Links a function whose parameters, and result when it has one, are pointers. */
  private MethodHandle downcall(Object function, boolean returns, int parameters)
      throws ReflectiveOperationException {
    Object layouts = Array.newInstance(layout, parameters);
    for (int i = 0; i < parameters; i++) {
      Array.set(layouts, i, address);
    }
    Object descriptor =
        returns ? describe.invoke(null, address, layouts) : describeVoid.invoke(null, layouts);
    return (MethodHandle)
        downcallHandle.invoke(linker, function, descriptor, Array.newInstance(option, 0));
  }

  private Object call(MethodHandle handle, Object... arguments)
      throws ReflectiveOperationException {
    return invoke.invoke(handle, (Object) arguments);
  }

  /** Copies an ASCII name with a terminating zero into an arena. */
  private Object ascii(Object arena, String text) throws ReflectiveOperationException {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    Object segment = allocate.invoke(arena, bytes.length + 1L, 1L);
    ((ByteBuffer) asByteBuffer.invoke(segment)).put(bytes).put((byte) 0);
    return segment;
  }

  private long address(Object segment) throws ReflectiveOperationException {
    return (Long) addressOf.invoke(segment);
  }
}
