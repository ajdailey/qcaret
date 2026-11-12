package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Startup on Java 21 and later: maps the platform to a target, relaunches on macOS when needed,
 * prepares the SWT natives, and runs {@code io.github.ajdailey.qcaret.launcher.Main} through a
 * {@link NestedJarClassLoader} over the target's fragment and the application jars.
 *
 * <p>Sets {@code swt.library.path} to the natives directory, so SWT never extracts a library
 * itself, and {@code qcaret.home} to the jar's installation directory: the parent of {@code lib/}
 * when the jar sits in one, else the jar's own directory.
 */
public final class Boot {
  /** Exit status for an unsupported Java, platform or launch mode. */
  public static final int EXIT_UNSUPPORTED = 2;

  /** Exit status when the SWT natives cannot be prepared. */
  public static final int EXIT_NATIVES = 3;

  private static final String LAUNCHER = "io.github.ajdailey.qcaret.launcher.Main";

  private Boot() {}

  /**
   * Starts QCaret and returns when the launcher returns, or with a nonzero status when startup
   * fails before the launcher runs. After a macOS relaunch it returns the child's status.
   *
   * @param args the command line
   * @return the exit status
   * @throws Exception when the launcher fails with an exception it does not handle
   */
  public static int start(String[] args) throws Exception {
    Target target = Target.current();
    if (target == null) {
      System.err.println(Target.unsupportedMessage());
      return EXIT_UNSUPPORTED;
    }
    Path jar = ownJar();
    if (jar == null) {
      System.err.println("QCaret must be started from qcaret.jar.");
      return EXIT_UNSUPPORTED;
    }
    Map<String, String> env = System.getenv();
    MacRelaunch.Decision decision =
        MacRelaunch.decide(
            target, env, ProcessHandle.current().pid(), System.getProperty(MacRelaunch.RELAUNCHED));
    if (decision == MacRelaunch.Decision.FAIL) {
      System.err.println("QCaret could not start on the macOS main thread.");
      return EXIT_UNSUPPORTED;
    }
    if (decision == MacRelaunch.Decision.RELAUNCH) {
      return MacRelaunch.relaunch(jar, args);
    }
    return run(target, jar, env, args);
  }

  private static int run(Target target, Path jar, Map<String, String> env, String[] args)
      throws Exception {
    Path home = home(jar);
    // The zip file systems back the class loader and stay open until the process exits.
    FileSystem outer = FileSystems.newFileSystem(jar);
    BootIndex index = BootIndex.read(outer);
    FileSystem fragment = FileSystems.newFileSystem(outer.getPath(BootIndex.fragment(target)));
    Path fragmentRoot = fragment.getPath("/");
    NativesLocation location;
    try {
      Path settings = settingsDirectory(settingsArgument(args));
      location =
          NativesLocation.resolve(
              home, settings, target, index.swtVersion(), env, System.getProperty("user.home"));
      Natives.prepare(
          location.dir(), location.writable(), outer, fragmentRoot, target, index.natives(target));
    } catch (Natives.MismatchException e) {
      System.err.println(e.getMessage());
      return EXIT_NATIVES;
    } catch (IOException | InvalidPathException e) {
      System.err.println("QCaret cannot prepare the SWT natives: " + e.getMessage());
      return EXIT_NATIVES;
    }
    System.setProperty("swt.library.path", location.dir().toString());
    System.setProperty("qcaret.home", home.toString());

    List<NestedJarClassLoader.Root> roots = new ArrayList<NestedJarClassLoader.Root>();
    roots.add(new NestedJarClassLoader.Root("swt-" + target.id() + ".jar", fragmentRoot));
    for (String lib : index.libs()) {
      FileSystem fs = FileSystems.newFileSystem(outer.getPath(BootIndex.LIB + lib));
      roots.add(new NestedJarClassLoader.Root(lib, fs.getPath("/")));
    }
    NestedJarClassLoader loader = new NestedJarClassLoader(roots);
    Thread.currentThread().setContextClassLoader(loader);
    Method main = Class.forName(LAUNCHER, true, loader).getMethod("main", String[].class);
    try {
      main.invoke(null, (Object) args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw e;
    }
    return 0;
  }

  /**
   * Returns the {@code --settings} value, as {@code --settings <dir>} or {@code --settings=<dir>},
   * from the options before {@code --}.
   *
   * @param args the command line
   * @return the last value given, or null
   */
  static String settingsArgument(String[] args) {
    String value = null;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.equals("--")) {
        break;
      }
      if (arg.equals("--settings") && i + 1 < args.length) {
        value = args[i + 1];
        i++;
      } else if (arg.startsWith("--settings=")) {
        value = arg.substring("--settings=".length());
      }
    }
    return value;
  }

  /**
   * Validates a {@code --settings} value: made absolute and normalized, created owner-only when
   * missing, then resolved to its real path, which must be a directory. An existing directory keeps
   * its permissions.
   *
   * @param value the option value, or null
   * @return the directory, or null when value is null
   * @throws IOException when the directory cannot be created or is not a directory
   */
  static Path settingsDirectory(String value) throws IOException {
    if (value == null) {
      return null;
    }
    if (value.isEmpty() || value.indexOf('\0') >= 0) {
      throw new IOException("--settings needs a directory");
    }
    Path dir = Path.of(value).toAbsolutePath().normalize();
    if (!Files.exists(dir)) {
      Natives.createOwnerOnly(dir);
    }
    Path real = dir.toRealPath();
    if (!Files.isDirectory(real)) {
      throw new IOException(real + " is not a directory");
    }
    return real;
  }

  /**
   * Returns {@code qcaret.home} for a jar location.
   *
   * @param jar the universal jar
   * @return the parent of {@code lib/} when the jar sits in one, else the jar's directory
   */
  static Path home(Path jar) {
    Path dir = jar.toAbsolutePath().getParent();
    if (dir == null) {
      return jar.toAbsolutePath();
    }
    Path name = dir.getFileName();
    if (name != null && name.toString().equals("lib") && dir.getParent() != null) {
      return dir.getParent();
    }
    return dir;
  }

  private static Path ownJar() throws URISyntaxException {
    CodeSource source = Boot.class.getProtectionDomain().getCodeSource();
    if (source == null || source.getLocation() == null) {
      return null;
    }
    Path path = Path.of(source.getLocation().toURI());
    return Files.isRegularFile(path) ? path : null;
  }
}
