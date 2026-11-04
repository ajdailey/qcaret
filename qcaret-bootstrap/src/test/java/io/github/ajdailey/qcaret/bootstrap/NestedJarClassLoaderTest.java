package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NestedJarClassLoaderTest {
  @TempDir Path temp;

  /** Loaded through the nested loader in the test. */
  public static final class Probe {}

  @Test
  void loadsClassesAndResourcesFromNestedJars() throws Exception {
    String classEntry = Probe.class.getName().replace('.', '/') + ".class";
    byte[] classBytes;
    try (InputStream in = Probe.class.getResourceAsStream("/" + classEntry)) {
      classBytes = in.readAllBytes();
    }
    Path a = temp.resolve("a.jar");
    Path b = temp.resolve("b.jar");
    TestJars.write(
        a,
        Map.of(
            classEntry,
            classBytes,
            "META-INF/services/x",
            "a".getBytes(StandardCharsets.UTF_8),
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\nImplementation-Version: 9.8.7\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8)));
    TestJars.write(b, Map.of("META-INF/services/x", "b".getBytes(StandardCharsets.UTF_8)));
    Path outer = temp.resolve("outer.jar");
    TestJars.write(
        outer, Map.of("lib/a.jar", Files.readAllBytes(a), "lib/b.jar", Files.readAllBytes(b)));

    try (FileSystem outerFs = FileSystems.newFileSystem(outer);
        FileSystem aFs = FileSystems.newFileSystem(outerFs.getPath("lib/a.jar"));
        FileSystem bFs = FileSystems.newFileSystem(outerFs.getPath("lib/b.jar"))) {
      NestedJarClassLoader loader =
          new NestedJarClassLoader(
              List.of(
                  new NestedJarClassLoader.Root("a.jar", aFs.getPath("/")),
                  new NestedJarClassLoader.Root("b.jar", bFs.getPath("/"))));

      Class<?> loaded = Class.forName(Probe.class.getName(), false, loader);
      assertSame(loader, loaded.getClassLoader());
      assertNotSame(Probe.class, loaded);
      assertEquals("9.8.7", loaded.getPackage().getImplementationVersion());

      List<String> contents = new ArrayList<String>();
      for (URL url : Collections.list(loader.getResources("META-INF/services/x"))) {
        assertEquals(NestedJarClassLoader.PROTOCOL, url.getProtocol());
        try (InputStream in = url.openStream()) {
          contents.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
      assertEquals(List.of("a", "b"), contents);
      assertNull(loader.getResource("META-INF/services/missing"));
      assertNull(loader.getResource("../outer.jar"));
    }
  }
}
