package io.github.ajdailey.qcaret.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Loads classes and resources from jars nested in the universal jar, read in place through the JDK
 * zip file system; nothing is extracted. {@code URLClassLoader} cannot do this: a {@code jar:} URL
 * cannot name an entry of a nested jar.
 *
 * <p>The parent is the platform class loader, so no QCaret or SWT class is visible through the
 * application class loader. Resources resolve to {@code qcaret-nested:} URLs; SWT calls {@code
 * getResource} on its own classes while loading natives and fails without one. The roots stay open
 * for the life of the process.
 */
final class NestedJarClassLoader extends ClassLoader {
  static final String PROTOCOL = "qcaret-nested";

  static {
    registerAsParallelCapable();
  }

  /**
   * One nested jar.
   *
   * @param name the jar's file name, used in resource URLs
   * @param root the root directory of the jar's zip file system
   */
  record Root(String name, Path root) {}

  private final List<Root> roots;
  private final List<ProtectionDomain> domains;
  private final List<Attributes> manifests;

  /**
   * Creates the loader.
   *
   * @param roots the nested jars, searched in order
   * @throws IOException when a root's code source URL cannot be formed
   */
  NestedJarClassLoader(List<Root> roots) throws IOException {
    super("qcaret", ClassLoader.getPlatformClassLoader());
    this.roots = List.copyOf(roots);
    List<ProtectionDomain> list = new ArrayList<ProtectionDomain>();
    for (Root root : this.roots) {
      CodeSource source = new CodeSource(url(root.name(), "", null), (Certificate[]) null);
      list.add(new ProtectionDomain(source, null, this, null));
    }
    this.domains = List.copyOf(list);
    List<Attributes> attributes = new ArrayList<Attributes>();
    for (Root root : this.roots) {
      attributes.add(mainAttributes(root));
    }
    this.manifests = List.copyOf(attributes);
  }

  private static Attributes mainAttributes(Root root) throws IOException {
    Path path = root.root().resolve("META-INF/MANIFEST.MF");
    if (!Files.isRegularFile(path)) {
      return new Attributes();
    }
    try (InputStream in = Files.newInputStream(path)) {
      return new Manifest(in).getMainAttributes();
    }
  }

  /** Defines the class's package from its jar's manifest, so implementation versions resolve. */
  private void definePackageOf(String className, int root) {
    int dot = className.lastIndexOf('.');
    if (dot <= 0) {
      return;
    }
    String name = className.substring(0, dot);
    if (getDefinedPackage(name) != null) {
      return;
    }
    Attributes a = manifests.get(root);
    try {
      definePackage(
          name,
          a.getValue(Attributes.Name.SPECIFICATION_TITLE),
          a.getValue(Attributes.Name.SPECIFICATION_VERSION),
          a.getValue(Attributes.Name.SPECIFICATION_VENDOR),
          a.getValue(Attributes.Name.IMPLEMENTATION_TITLE),
          a.getValue(Attributes.Name.IMPLEMENTATION_VERSION),
          a.getValue(Attributes.Name.IMPLEMENTATION_VENDOR),
          null);
    } catch (IllegalArgumentException e) {
      // Another thread defined it first.
      return;
    }
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    String entry = name.replace('.', '/') + ".class";
    for (int i = 0; i < roots.size(); i++) {
      Path path = resolve(roots.get(i), entry);
      if (path != null) {
        try {
          byte[] bytes = Files.readAllBytes(path);
          definePackageOf(name, i);
          return defineClass(name, bytes, 0, bytes.length, domains.get(i));
        } catch (IOException e) {
          throw new ClassNotFoundException(name, e);
        }
      }
    }
    throw new ClassNotFoundException(name);
  }

  @Override
  protected URL findResource(String name) {
    for (Root root : roots) {
      Path path = resolve(root, name);
      if (path != null) {
        return resourceUrl(root, name, path);
      }
    }
    return null;
  }

  @Override
  protected Enumeration<URL> findResources(String name) {
    List<URL> urls = new ArrayList<URL>();
    for (Root root : roots) {
      Path path = resolve(root, name);
      if (path != null) {
        URL url = resourceUrl(root, name, path);
        if (url != null) {
          urls.add(url);
        }
      }
    }
    return Collections.enumeration(urls);
  }

  private static Path resolve(Root root, String name) {
    if (name.isEmpty() || name.startsWith("/") || name.contains("..")) {
      return null;
    }
    Path path = root.root().resolve(name);
    return Files.isRegularFile(path) ? path : null;
  }

  private static URL resourceUrl(Root root, String name, Path path) {
    try {
      return url(root.name(), name, path);
    } catch (IOException e) {
      return null;
    }
  }

  private static URL url(String jar, String name, Path path) throws IOException {
    try {
      URI uri = new URI(PROTOCOL, null, "/" + jar + "/" + name, null);
      return URL.of(uri, new Handler(path));
    } catch (URISyntaxException | MalformedURLException e) {
      throw new IOException("Cannot form a resource URL for " + name, e);
    }
  }

  /** Opens the nested entry a {@code qcaret-nested:} URL names. */
  private static final class Handler extends URLStreamHandler {
    private final Path path;

    Handler(Path path) {
      this.path = path;
    }

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
      if (path == null) {
        throw new IOException("No content: " + url);
      }
      return new URLConnection(url) {
        @Override
        public void connect() {
          connected = true;
        }

        @Override
        public InputStream getInputStream() throws IOException {
          return Files.newInputStream(path);
        }

        @Override
        public long getContentLengthLong() {
          try {
            return Files.size(path);
          } catch (IOException e) {
            return -1;
          }
        }
      };
    }
  }
}
