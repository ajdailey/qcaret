package io.github.ajdailey.qcaret.ui.swt.platform;

/** The platform a test runs on, for tests that skip or expect a platform's behavior. */
public final class RunningPlatform {
  /** True on win32. */
  public static final boolean WIN32 = "win32".equals(PlatformTraits.PLATFORM.name());

  /** True on cocoa. */
  public static final boolean COCOA = "cocoa".equals(PlatformTraits.PLATFORM.name());

  /** True on GTK 3 or GTK 4. */
  public static final boolean GTK = "gtk".equals(PlatformTraits.PLATFORM.name());

  /** True on GTK 4, as SWT loaded it. */
  public static final boolean GTK4 = PlatformTraits.PLATFORM instanceof Gtk4Traits;

  private RunningPlatform() {}
}
