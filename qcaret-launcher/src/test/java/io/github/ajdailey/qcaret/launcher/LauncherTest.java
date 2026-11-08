package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.ipc.InstanceClient;
import io.github.ajdailey.qcaret.app.ipc.InstanceServer;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.workspace.HomeFolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LauncherTest {
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();

  @TempDir Path temp;

  private int run(String... args) {
    return new Launcher(
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8))
        .run(args);
  }

  @Test
  void aRefusedHandoffToALivePrimaryRunsBesideIt() throws Exception {
    List<FileRequest> files = new ArrayList<FileRequest>();
    for (int i = 0; i < 300; i++) {
      files.add(new FileRequest(temp.resolve("mock" + i + ".txt")));
    }
    StartupRequest request = new StartupRequest(files, null, null, null, null, null, false);
    Launcher launcher =
        new Launcher(
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    assertNull(InstanceClient.refusal(temp, System.getProperty("user.name")));
    try (InstanceServer live = InstanceServer.claim(temp)) {
      assertNotNull(live);
      assertEquals(Launcher.Handoff.BESIDE, launcher.handOff(temp, request));
    }
  }

  private static StartupRequest handoff(Path file) {
    return StartupRequest.handoff(
        List.of(new FileRequest(file)), false, null, null, null, null, null, null);
  }

  private Launcher launcher() {
    return new Launcher(
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @Test
  void aLaunchClaimsTheLockAPrimaryReleasedDuringItsHandoff() throws Exception {
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      final InstanceServer ending = InstanceServer.claim(temp);
      assertNotNull(ending);
      assertTrue(ending.start(workers));
      workers.start(
          Workers.Kind.IPC,
          new Runnable() {
            @Override
            public void run() {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              ending.close();
            }
          });
      Launcher launcher = launcher();
      assertEquals(
          Launcher.Handoff.PRIMARY, launcher.handOff(temp, handoff(temp.resolve("a.txt"))));
      assertNotNull(launcher.primary());
      launcher.primary().close();
    }
  }

  @Test
  void helpPrintsUsage() {
    assertEquals(0, run("-h"));
    assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("Usage: qcaret"));
  }

  @Test
  void versionPrintsVersion() {
    assertEquals(0, run("--version"));
    assertTrue(out.toString(StandardCharsets.UTF_8).startsWith("qcaret "));
  }

  @Test
  void unknownOptionExits1() {
    assertEquals(1, run("--bogus"));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown option --bogus"));
  }

  @Test
  void settingsThatIsAFileExits1() throws Exception {
    Path file = Files.createFile(temp.resolve("file"));
    assertEquals(1, run("--settings", file.toString()));
  }

  @Test
  void explicitAndPortableSettingsOnLinuxMoveUserHome() {
    String home = System.getProperty("user.home");
    String saved = System.clearProperty(HomeFolder.PROPERTY);
    try {
      PlatformSetup.apply("Windows 11", new SettingsLocation(SettingsMode.EXPLICIT, temp));
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.PER_USER, temp));
      assertEquals(home, System.getProperty("user.home"));
      assertNull(System.getProperty(HomeFolder.PROPERTY));
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.EXPLICIT, temp));
      assertEquals(temp.toString(), System.getProperty("user.home"));
      Path portable = temp.resolve("portable");
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.PORTABLE, portable));
      assertEquals(portable.toString(), System.getProperty("user.home"));
      assertEquals(home, System.getProperty(HomeFolder.PROPERTY));
    } finally {
      System.setProperty("user.home", home);
      System.clearProperty(HomeFolder.PROPERTY);
      if (saved != null) {
        System.setProperty(HomeFolder.PROPERTY, saved);
      }
    }
  }

  @Test
  void onWindowsTheWebView2DataFolderIsInTheSettingsCache() throws Exception {
    String before = System.getProperty(PlatformSetup.EDGE_DATA_DIR);
    String home = System.getProperty("user.home");
    try {
      System.clearProperty(PlatformSetup.EDGE_DATA_DIR);
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.PER_USER, temp));
      assertNull(System.getProperty(PlatformSetup.EDGE_DATA_DIR));

      Path roaming = temp.resolve("roaming");
      SettingsLocation perUser =
          SettingsLocation.resolve(
              null, null, "Windows 11", Map.of("APPDATA", roaming.toString()), home);
      assertEquals(SettingsMode.PER_USER, perUser.mode());
      assertWebView2(roaming.resolve("QCaret"), perUser);

      Path installed = Files.createDirectories(temp.resolve("installed"));
      Files.writeString(installed.resolve(SettingsLocation.MARKER), "");
      SettingsLocation portable =
          SettingsLocation.resolve(
              null,
              installed.toString(),
              "Windows 11",
              Map.of("APPDATA", roaming.toString()),
              home);
      assertEquals(SettingsMode.PORTABLE, portable.mode());
      assertWebView2(installed, portable);

      Path explicit = temp.resolve("explicit");
      assertWebView2(explicit, new SettingsLocation(SettingsMode.EXPLICIT, explicit));
      assertEquals(home, System.getProperty("user.home"));
    } finally {
      if (before == null) {
        System.clearProperty(PlatformSetup.EDGE_DATA_DIR);
      } else {
        System.setProperty(PlatformSetup.EDGE_DATA_DIR, before);
      }
    }
  }

  private static void assertWebView2(Path settings, SettingsLocation location) {
    PlatformSetup.apply("Windows 11", location);
    assertEquals(
        settings.resolve("cache").resolve("webview2").toString(),
        System.getProperty(PlatformSetup.EDGE_DATA_DIR));
  }
}
