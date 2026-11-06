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
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
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
  void sixteenLaunchesOfAnEndingPrimaryLeaveOnePrimaryAndTheRestHanded() throws Exception {
    final List<Launcher.Handoff> outcomes = new CopyOnWriteArrayList<Launcher.Handoff>();
    final List<StartupRequest> received = new CopyOnWriteArrayList<StartupRequest>();
    final List<InstanceServer> successors = new CopyOnWriteArrayList<InstanceServer>();
    final InstanceServer.Receiver receiver =
        new InstanceServer.Receiver() {
          @Override
          public boolean deliver(StartupRequest request) {
            received.add(request);
            return true;
          }
        };
    final int launches = 16;
    try (Workers workers = new Workers(1, new Launcher.FailureLogger())) {
      InstanceServer ending = InstanceServer.claim(temp);
      assertNotNull(ending);
      assertTrue(ending.start(workers));
      for (int i = 0; i < launches; i++) {
        final StartupRequest request = handoff(temp.resolve("launch" + i + ".txt"));
        final Workers threads = workers;
        workers.start(
            Workers.Kind.IPC,
            new Runnable() {
              @Override
              public void run() {
                Launcher launcher = launcher();
                Launcher.Handoff outcome = launcher.handOff(temp, request);
                if (outcome == Launcher.Handoff.PRIMARY) {
                  successors.add(launcher.primary());
                  launcher.primary().start(threads);
                  launcher.primary().deliverTo(receiver);
                }
                outcomes.add(outcome);
              }
            });
      }
      Thread.sleep(200);
      ending.close();
      boolean ended =
          Poll.until(
              new BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                  return outcomes.size() == launches;
                }
              },
              Duration.ofSeconds(30));
      for (InstanceServer successor : successors) {
        successor.close();
      }
      assertTrue(ended, outcomes.toString());
    }
    assertEquals(1, Collections.frequency(outcomes, Launcher.Handoff.PRIMARY), outcomes.toString());
    assertEquals(
        launches - 1,
        Collections.frequency(outcomes, Launcher.Handoff.HANDED),
        outcomes.toString());
    assertEquals(launches - 1, received.size());
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
    try {
      PlatformSetup.apply("Windows 11", new SettingsLocation(SettingsMode.EXPLICIT, temp));
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.PER_USER, temp));
      assertEquals(home, System.getProperty("user.home"));
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.EXPLICIT, temp));
      assertEquals(temp.toString(), System.getProperty("user.home"));
      Path portable = temp.resolve("portable");
      PlatformSetup.apply("Linux", new SettingsLocation(SettingsMode.PORTABLE, portable));
      assertEquals(portable.toString(), System.getProperty("user.home"));
    } finally {
      System.setProperty("user.home", home);
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
