package io.github.ajdailey.qcaret.core.util.os;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OsTraitsTest {
  private static final OsTraits WINDOWS = OsTraits.of("Windows 11");
  private static final OsTraits MAC = OsTraits.of("Mac OS X");
  private static final OsTraits LINUX = OsTraits.of("Linux");

  @ParameterizedTest
  @CsvSource({
    "Windows 11, windows",
    "Windows Server 2022, windows",
    "Mac OS X, mac",
    "Linux, linux",
    "FreeBSD, linux"
  })
  void theNameChoosesTheSystem(String osName, String expected) {
    OsTraits expectedTraits =
        switch (expected) {
          case "windows" -> WINDOWS;
          case "mac" -> MAC;
          default -> LINUX;
        };
    assertSame(expectedTraits, OsTraits.of(osName));
    assertSame(expectedTraits, OsTraits.of(osName.toUpperCase(Locale.ROOT)));
    assertSame(LINUX, OsTraits.of(null));
  }

  @Test
  void theProcessTraitsFollowOsName() {
    assertSame(OsTraits.of(System.getProperty("os.name")), OsTraits.current());
  }

  /** Lists the yes-or-no answers and the lock kind. */
  private static String answers(OsTraits os) {
    return "ignoresCase="
        + os.fileNamesIgnoreCase()
        + " lock="
        + os.fileLock()
        + " devices="
        + os.opensDeviceNames()
        + " confines="
        + os.confinesToSettings()
        + " webView2="
        + os.webView2();
  }

  @Test
  void eachSystemAnswersItsQuestions() {
    assertEquals(
        "ignoresCase=true lock=CHANNEL devices=true confines=false webView2=true",
        answers(WINDOWS));
    assertEquals(
        "ignoresCase=true lock=FLOCK_MACOS devices=false confines=false webView2=false",
        answers(MAC));
    assertEquals(
        "ignoresCase=false lock=FLOCK_LINUX devices=false confines=true webView2=false",
        answers(LINUX));
  }

  @Test
  void perUserSettingsFollowTheSystem(@TempDir Path temp) {
    Path home = temp.resolve("home");
    Path xdg = temp.resolve("xdg");
    assertEquals(
        xdg.resolve("qcaret"), LINUX.userSettings(Map.of("XDG_CONFIG_HOME", xdg.toString()), home));
    assertEquals(
        home.resolve(".config").resolve("qcaret"),
        LINUX.userSettings(Map.of("XDG_CONFIG_HOME", "relative"), home));
    Path appData = temp.resolve("Roaming");
    assertEquals(
        appData.resolve("QCaret"),
        WINDOWS.userSettings(Map.of("APPDATA", appData.toString()), home));
    assertEquals(
        home.resolve("AppData").resolve("Roaming").resolve("QCaret"),
        WINDOWS.userSettings(Map.<String, String>of(), home));
    assertEquals(
        home.resolve("Library").resolve("Application Support").resolve("QCaret"),
        MAC.userSettings(Map.<String, String>of(), home));
  }

  @Test
  void eachSystemHasItsFontFolders() {
    assertEquals(
        List.of(Path.of("C:/Windows", "Fonts"), Path.of("C:/Local", "Microsoft/Windows/Fonts")),
        WINDOWS.fontDirectories(
            Map.of("WINDIR", "C:/Windows", "LOCALAPPDATA", "C:/Local"), "C:/home"));
    assertEquals(
        List.of(
            Path.of("/System/Library/Fonts"),
            Path.of("/Library/Fonts"),
            Path.of("/home/mock/Library/Fonts")),
        MAC.fontDirectories(Map.<String, String>of(), "/home/mock"));
    assertEquals(
        List.of(
            Path.of("/home/mock/.fonts"),
            Path.of("/home/mock/.local/share/fonts"),
            Path.of("/usr/local/share/fonts"),
            Path.of("/usr/share/fonts")),
        LINUX.fontDirectories(Map.<String, String>of(), "/home/mock"));
    List<Path> xdg =
        LINUX.fontDirectories(
            Map.of("XDG_DATA_HOME", "/d", "XDG_DATA_DIRS", "/a:/b"), "/home/mock");
    assertTrue(xdg.containsAll(List.of(Path.of("/d/fonts"), Path.of("/a/fonts"))));
    assertTrue(xdg.contains(Path.of("/b/fonts")));
  }

  @Test
  void onlyANonEmptyAbsolutePathIsAccepted() {
    Path absolute = Path.of("/a").toAbsolutePath();
    assertEquals(absolute.resolve("c"), EnvPaths.absolute(absolute.resolve("b/../c").toString()));
    assertNull(EnvPaths.absolute(null));
    assertNull(EnvPaths.absolute(""));
    assertNull(EnvPaths.absolute("relative"));
    assertNull(EnvPaths.absolute("/a\0b"));
  }

  @Test
  void theDescriptionIsNameVersionAndArchitecture() {
    assertEquals(
        System.getProperty("os.name")
            + " "
            + System.getProperty("os.version")
            + " "
            + System.getProperty("os.arch"),
        OsTraits.description());
  }
}
