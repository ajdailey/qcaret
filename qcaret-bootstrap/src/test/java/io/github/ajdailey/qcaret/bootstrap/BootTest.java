package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BootTest {
  @TempDir Path temp;

  @Test
  void findsSettingsBeforeDoubleDash() {
    assertEquals("a", Boot.settingsArgument(new String[] {"-m", "--settings", "a", "f"}));
    assertEquals("b", Boot.settingsArgument(new String[] {"--settings=b"}));
    assertNull(Boot.settingsArgument(new String[] {"--", "--settings", "c"}));
    assertNull(Boot.settingsArgument(new String[] {"--settings"}));
  }

  @Test
  void homeIsParentOfLib() {
    Path lib = temp.resolve("qcaret").resolve("lib");
    assertEquals(temp.resolve("qcaret"), Boot.home(lib.resolve("qcaret.jar")));
    assertEquals(temp, Boot.home(temp.resolve("qcaret.jar")));
  }

  @Test
  void settingsDirectoryIsCreated() throws Exception {
    Path dir = Boot.settingsDirectory(temp.resolve("s").resolve("t").toString());
    assertTrue(Files.isDirectory(dir));
  }

  @Test
  void aCreatedSettingsDirectoryIsOwnerOnlyAndAnExistingOneKeepsItsPermissions() throws Exception {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
    Path created = Boot.settingsDirectory(temp.resolve("created").toString());
    assertEquals(
        "rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(created)));

    Path shared = Files.createDirectory(temp.resolve("shared"));
    Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxr-x"));
    Boot.settingsDirectory(shared.toString());
    assertEquals("rwxrwxr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)));
  }

  @Test
  void nativesLocationPrecedence() throws Exception {
    Path home = Files.createDirectory(temp.resolve("home"));
    Map<String, String> env = Map.of("XDG_CACHE_HOME", temp.resolve("xdg").toString());
    NativesLocation user =
        NativesLocation.resolve(home, null, Target.LINUX_X86_64, "3.135.0", env, "/u");
    assertEquals(
        temp.resolve("xdg").resolve("qcaret").resolve("swt").resolve("3.135.0-linux-x86_64"),
        user.dir());
    assertTrue(user.writable());

    Path settings = temp.resolve("settings");
    assertEquals(
        settings.resolve("cache").resolve("swt").resolve("3.135.0-linux-x86_64"),
        NativesLocation.resolve(home, settings, Target.LINUX_X86_64, "3.135.0", env, "/u").dir());

    // On Linux, a marker beside bin/qcaret; the installation directory's wins.
    Path bin = Files.createDirectory(home.resolve("bin"));
    Files.createFile(bin.resolve("qcaret.conf"));
    assertEquals(
        bin.resolve("cache").resolve("swt").resolve("3.135.0-linux-x86_64"),
        NativesLocation.resolve(home, null, Target.LINUX_X86_64, "3.135.0", env, "/u").dir());
    assertEquals(
        Path.of(
            "/u", "Library", "Caches", "io.github.ajdailey.qcaret", "swt", "3.135.0-macos-aarch64"),
        NativesLocation.resolve(home, null, Target.MACOS_AARCH64, "3.135.0", env, "/u").dir());
    assertEquals(
        Path.of("/u", "AppData", "Local", "QCaret", "cache", "swt", "3.135.0-windows-x86_64"),
        NativesLocation.resolve(home, null, Target.WINDOWS_X86_64, "3.135.0", Map.of(), "/u")
            .dir());

    Files.createFile(home.resolve("qcaret.conf"));
    assertEquals(
        home.resolve("cache").resolve("swt").resolve("3.135.0-linux-x86_64"),
        NativesLocation.resolve(home, null, Target.LINUX_X86_64, "3.135.0", env, "/u").dir());

    Path bundled = Files.createDirectories(home.resolve("lib").resolve("swt"));
    NativesLocation bundle =
        NativesLocation.resolve(home, settings, Target.LINUX_X86_64, "3.135.0", env, "/u");
    assertEquals(bundled, bundle.dir());
    assertFalse(bundle.writable());
  }

  @Test
  void relativeXdgCacheHomeIsIgnored() {
    Path dir =
        NativesLocation.resolve(
                temp, null, Target.LINUX_X86_64, "3", Map.of("XDG_CACHE_HOME", "rel"), "/u")
            .dir();
    assertEquals(Path.of("/u", ".cache", "qcaret", "swt", "3-linux-x86_64"), dir);
  }

  @Test
  void macRelaunchDecision() {
    Map<String, String> onMain = Map.of("JAVA_STARTED_ON_FIRST_THREAD_7", "1");
    assertEquals(
        MacRelaunch.Decision.PROCEED, MacRelaunch.decide(Target.LINUX_X86_64, Map.of(), 7, null));
    assertEquals(
        MacRelaunch.Decision.PROCEED, MacRelaunch.decide(Target.MACOS_AARCH64, onMain, 7, null));
    assertEquals(
        MacRelaunch.Decision.RELAUNCH, MacRelaunch.decide(Target.MACOS_AARCH64, Map.of(), 7, null));
    assertEquals(
        MacRelaunch.Decision.FAIL, MacRelaunch.decide(Target.MACOS_X86_64, Map.of(), 7, "true"));
  }

  @Test
  void relaunchCommandKeepsEachArgument() {
    assertEquals(
        List.of(
            "/j/bin/java",
            "-XstartOnFirstThread",
            "-Dqcaret.relaunched=true",
            "-jar",
            Path.of("/q/lib/qcaret.jar").toString(),
            "a b",
            "; rm x"),
        MacRelaunch.command(
            "/j/bin/java", Path.of("/q/lib/qcaret.jar"), new String[] {"a b", "; rm x"}));
  }
}
