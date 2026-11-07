package io.github.ajdailey.qcaret.ui.swt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A private X server, session bus and {@code ibus-daemon}, and the {@link IBusDriver} JVM inside
 * them. Each process gets an environment built from nothing, with its own {@code HOME} and {@code
 * XDG_*} directories, so no key event or setting reaches the desktop session. GLib double-forks
 * what {@code ibus-daemon} spawns, so {@link #close} finds those processes by a mark in their
 * environment rather than by parentage.
 */
final class IBusSession implements AutoCloseable {
  static final String ENGINE = "table:cangjie5";
  private static final String MARK = "QCARET_IBUS_RUN";
  private static final Duration START_BOUND = Duration.ofSeconds(30);
  private static final Duration DRIVER_BOUND = Duration.ofMinutes(3);
  private static final String[] TOOLS = {
    "Xvfb", "dbus-run-session", "ibus-daemon", "ibus", "xdotool"
  };

  private final Path home;
  private final Path logs;
  private final String mark = UUID.randomUUID().toString();
  private final List<Process> processes = new ArrayList<Process>();
  private String display;

  /** Setup failed on a host that cannot run the input method. */
  static final class Unavailable extends Exception {
    private static final long serialVersionUID = 1L;

    Unavailable(String reason) {
      super(reason);
    }
  }

  /**
   * Prepares a session; {@link #start} runs it.
   *
   * @param home the private directory; its path must leave room for a Unix socket name
   * @param logs where each process's output goes
   */
  IBusSession(Path home, Path logs) {
    this.home = home;
    this.logs = logs;
  }

  /**
   * Looks for the tools on {@code PATH}, starting nothing.
   *
   * @return the first missing tool, or null with all present
   */
  static String missingTool() {
    String path = System.getenv("PATH");
    for (String tool : TOOLS) {
      boolean found = false;
      if (path != null) {
        for (String dir : path.split(File.pathSeparator)) {
          found |= Files.isExecutable(Path.of(dir, tool));
        }
      }
      if (!found) {
        return tool;
      }
    }
    return null;
  }

  /**
   * Starts the X server and the daemon and selects {@link #ENGINE}.
   *
   * @throws Unavailable when either does not start or the engine is not installed
   * @throws IOException when a log cannot be written
   * @throws InterruptedException when interrupted during a wait
   */
  void start() throws Unavailable, IOException, InterruptedException {
    Files.createDirectories(logs);
    for (String dir : List.of(".config", ".cache", ".local/share", "run")) {
      Files.createDirectories(home.resolve(dir));
    }
    Files.setPosixFilePermissions(
        home.resolve("run"), PosixFilePermissions.fromString("rwx------"));
    display = startDisplay();
    spawn(
        "daemon.log",
        "dbus-run-session",
        "--",
        "ibus-daemon",
        "--panel=disable",
        "--emoji-extension=disable",
        "--config=disable");
    selectEngine();
  }

  private String startDisplay() throws Unavailable, IOException, InterruptedException {
    Path number = home.resolve("display");
    ProcessBuilder xvfb =
        new ProcessBuilder(
                "Xvfb", "-displayfd", "1", "-screen", "0", "1280x1024x24", "-nolisten", "tcp")
            .redirectOutput(number.toFile())
            .redirectError(logs.resolve("xvfb.log").toFile());
    environment(xvfb);
    Process process = xvfb.start();
    processes.add(process);
    long deadline = System.nanoTime() + START_BOUND.toNanos();
    while (System.nanoTime() < deadline && process.isAlive()) {
      String value = Files.readString(number);
      if (value.endsWith("\n")) {
        return ":" + value.trim();
      }
      Thread.sleep(50);
    }
    throw new Unavailable("Xvfb reported no display; see xvfb.log");
  }

  private void selectEngine() throws Unavailable, IOException, InterruptedException {
    long deadline = System.nanoTime() + START_BOUND.toNanos();
    while (System.nanoTime() < deadline) {
      ProcessBuilder select =
          new ProcessBuilder("ibus", "engine", ENGINE)
              .redirectErrorStream(true)
              .redirectOutput(logs.resolve("engine.log").toFile());
      environment(select);
      Process process = select.start();
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      } else if (process.exitValue() == 0) {
        return;
      }
      Thread.sleep(200);
    }
    throw new Unavailable("no working ibus-daemon with " + ENGINE + "; see engine.log");
  }

  /**
   * Runs the driver JVM to its end.
   *
   * @param results the file it writes
   * @throws Unavailable when it does not end within its bound
   * @throws IOException when it cannot start
   * @throws InterruptedException when interrupted during the wait
   */
  void drive(Path results) throws Unavailable, IOException, InterruptedException {
    String classPath = System.getProperty("surefire.test.class.path");
    if (classPath == null) {
      classPath = System.getProperty("java.class.path");
    }
    Process driver =
        spawn(
            "driver.log",
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Duser.home=" + home,
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classPath,
            IBusDriver.class.getName(),
            home.toString(),
            results.toString());
    if (!driver.waitFor(DRIVER_BOUND.toSeconds(), TimeUnit.SECONDS)) {
      throw new Unavailable("the driver ran past " + DRIVER_BOUND + "; see driver.log");
    }
  }

  private Process spawn(String log, String... command) throws IOException {
    ProcessBuilder builder =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(logs.resolve(log).toFile());
    environment(builder);
    Process process = builder.start();
    processes.add(process);
    return process;
  }

  private void environment(ProcessBuilder builder) {
    Map<String, String> env = builder.environment();
    String path = env.get("PATH");
    env.clear();
    env.put("PATH", path);
    env.put(MARK, mark);
    env.put("HOME", home.toString());
    env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
    env.put("XDG_CACHE_HOME", home.resolve(".cache").toString());
    env.put("XDG_DATA_HOME", home.resolve(".local/share").toString());
    env.put("XDG_RUNTIME_DIR", home.resolve("run").toString());
    env.put("LC_ALL", "C.UTF-8");
    env.put("GTK_IM_MODULE", "ibus");
    env.put("GDK_BACKEND", "x11");
    env.put("GSETTINGS_BACKEND", "memory");
    env.put("NO_AT_BRIDGE", "1");
    // dbus-run-session replaces this for the daemon; nothing else needs a session bus.
    env.put("DBUS_SESSION_BUS_ADDRESS", "disabled:");
    if (display != null) {
      env.put("DISPLAY", display);
    }
  }

  /** Stops every process of this run, those the daemon spawned included. */
  @Override
  public void close() throws IOException, InterruptedException {
    for (Process process : processes) {
      process.destroy();
    }
    for (Process process : processes) {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    List<ProcessHandle> left = marked();
    while (!left.isEmpty() && System.nanoTime() < deadline) {
      for (ProcessHandle process : left) {
        process.destroyForcibly();
      }
      Thread.sleep(50);
      left = marked();
    }
    if (!left.isEmpty()) {
      throw new IOException("still running: " + left);
    }
  }

  /** Returns the live processes whose environment carries this run's mark. */
  private List<ProcessHandle> marked() {
    byte[] wanted = (MARK + "=" + mark).getBytes(StandardCharsets.US_ASCII);
    List<ProcessHandle> found = new ArrayList<ProcessHandle>();
    File[] entries = new File("/proc").listFiles();
    if (entries == null) {
      return found;
    }
    for (File entry : entries) {
      if (entry.getName().chars().allMatch(Character::isDigit)) {
        try {
          byte[] env = Files.readAllBytes(entry.toPath().resolve("environ"));
          if (contains(env, wanted)) {
            ProcessHandle.of(Long.parseLong(entry.getName())).ifPresent(found::add);
          }
        } catch (IOException e) {
          // Another user's process, or one that has ended.
          continue;
        }
      }
    }
    return found;
  }

  private static boolean contains(byte[] text, byte[] wanted) {
    for (int i = 0; i + wanted.length <= text.length; i++) {
      int j = 0;
      while (j < wanted.length && text[i + j] == wanted[j]) {
        j++;
      }
      if (j == wanted.length) {
        return true;
      }
    }
    return false;
  }
}
