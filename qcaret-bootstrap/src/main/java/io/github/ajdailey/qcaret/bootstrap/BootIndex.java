package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The build-time index {@code META-INF/qcaret/index.properties} of the universal jar: the SWT
 * version, the nested application jars and the native libraries of each target. Every name in it
 * matches {@code [A-Za-z0-9._-]+}; a name that does not fails the read.
 */
final class BootIndex {
  static final String ROOT = "META-INF/qcaret/";
  static final String INDEX = ROOT + "index.properties";
  static final String LIB = ROOT + "lib/";
  static final String SWT = ROOT + "swt/";
  static final String NATIVES = ROOT + "natives/";

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");
  private static final long MAX_INDEX_BYTES = 64 * 1024;

  private final String swtVersion;
  private final List<String> libs;
  private final Properties properties;

  private BootIndex(String swtVersion, List<String> libs, Properties properties) {
    this.swtVersion = swtVersion;
    this.libs = libs;
    this.properties = properties;
  }

  /**
   * Reads the index from the root of the universal jar.
   *
   * @param jar the jar's zip file system
   * @return the index
   * @throws IOException when the index is missing, too large or holds an invalid name
   */
  static BootIndex read(FileSystem jar) throws IOException {
    Path path = jar.getPath(INDEX);
    if (!Files.isRegularFile(path)) {
      throw new IOException(INDEX + " is missing; this jar is not a QCaret build");
    }
    if (Files.size(path) > MAX_INDEX_BYTES) {
      throw new IOException(INDEX + " is larger than " + MAX_INDEX_BYTES + " bytes");
    }
    Properties properties = new Properties();
    try (InputStream in = Files.newInputStream(path)) {
      properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
    String version = properties.getProperty("swt.version");
    if (version == null || !NAME.matcher(version).matches()) {
      throw new IOException(INDEX + " has no valid swt.version");
    }
    return new BootIndex(version, names(properties, "lib"), properties);
  }

  /**
   * Returns the SWT version the jar carries.
   *
   * @return for example {@code 3.135.0}
   */
  String swtVersion() {
    return swtVersion;
  }

  /**
   * Returns the nested application jar names under {@link #LIB}, in class path order.
   *
   * @return the names
   */
  List<String> libs() {
    return libs;
  }

  /**
   * Returns the native library names in the target's SWT fragment.
   *
   * @param target the target
   * @return the names
   * @throws IOException when the index lists no natives for the target or holds an invalid name
   */
  List<String> natives(Target target) throws IOException {
    List<String> names = names(properties, "natives." + target.id());
    if (names.isEmpty()) {
      throw new IOException(INDEX + " lists no SWT natives for " + target.id());
    }
    return names;
  }

  /**
   * Returns the path of the target's SWT fragment inside the universal jar.
   *
   * @param target the target
   * @return {@code META-INF/qcaret/swt/<target>.jar}
   */
  static String fragment(Target target) {
    return SWT + target.id() + ".jar";
  }

  /**
   * Returns the path of a native's SHA-256 digest inside the universal jar.
   *
   * @param target the target
   * @param name the library name, already validated
   * @return {@code META-INF/qcaret/natives/<target>/<name>.sha256}
   */
  static String digest(Target target, String name) {
    return NATIVES + target.id() + "/" + name + ".sha256";
  }

  /**
   * Reports whether a name from the index or the jar is a plain file name.
   *
   * @param name the name
   * @return true when it matches {@code [A-Za-z0-9._-]+} and is not {@code .} or {@code ..}
   */
  static boolean validName(String name) {
    return name != null && NAME.matcher(name).matches() && !name.equals(".") && !name.equals("..");
  }

  private static List<String> names(Properties properties, String key) throws IOException {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      return Collections.emptyList();
    }
    List<String> names = new ArrayList<String>();
    for (String part : value.split(",")) {
      String name = part.strip();
      if (!validName(name)) {
        throw new IOException(INDEX + " key " + key + " holds an invalid name");
      }
      names.add(name);
    }
    return Collections.unmodifiableList(names);
  }
}
