package io.github.ajdailey.qcaret.app.about;

import io.github.ajdailey.qcaret.app.cli.Version;
import io.github.ajdailey.qcaret.app.config.SettingsLocation;
import io.github.ajdailey.qcaret.app.config.SettingsMode;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.core.util.Streams;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * What the About dialog shows. Framework-free: the view supplies its toolkit line and lays the
 * record out.
 *
 * @param version the project version from the jar manifest
 * @param java Java version and vendor
 * @param os OS name, version and architecture
 * @param toolkit UI toolkit version and platform
 * @param settingsDirectory the settings directory
 * @param settingsMode how the settings directory was chosen
 */
public record AboutInfo(
    String version,
    String java,
    String os,
    String toolkit,
    Path settingsDirectory,
    SettingsMode settingsMode) {
  /** The project page; QCaret opens it, or {@link #ISSUES_URL}, only on the user's action. */
  public static final String PROJECT_URL = "https://github.com/ajdailey/qcaret";

  /** The project's issue page, which Help > Report an Issue opens. */
  public static final String ISSUES_URL = PROJECT_URL + "/issues";

  private static final String[] LICENSE_FILES = {"LICENSE", "NOTICE", "THIRD-PARTY.txt"};
  private static final int MAX_LICENSE_BYTES = 1 << 20;

  /**
   * Collects the runtime facts from system properties.
   *
   * @param toolkit the toolkit line from the view
   * @param settings the settings directory
   * @return the record
   */
  public static AboutInfo collect(String toolkit, SettingsLocation settings) {
    String java =
        System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")";
    return new AboutInfo(
        Version.get(),
        java,
        OsTraits.description(),
        toolkit,
        settings.directory(),
        settings.mode());
  }

  /**
   * Returns the lines Copy Details puts on the clipboard.
   *
   * @return version and runtime lines
   */
  public List<String> details() {
    UiText mode =
        switch (settingsMode) {
          case EXPLICIT -> UiText.SETTINGS_EXPLICIT;
          case PORTABLE -> UiText.SETTINGS_PORTABLE;
          case PER_USER -> UiText.SETTINGS_PER_USER;
        };
    List<String> lines = new ArrayList<String>();
    lines.add(UiText.DETAILS_VERSION.format(version));
    lines.add(UiText.DETAILS_JAVA.format(java));
    lines.add(UiText.DETAILS_OS.format(os));
    lines.add(UiText.DETAILS_TOOLKIT.format(toolkit));
    lines.add(UiText.DETAILS_SETTINGS.format(settingsDirectory, mode.text()));
    return lines;
  }

  /**
   * Reads {@code LICENSE}, {@code NOTICE} and {@code THIRD-PARTY.txt} from the jar, each under a
   * heading. Blocking; call it on a worker.
   *
   * @return the text; a file missing from the class path is named as missing
   * @throws IOException when a file cannot be read or is over 1 MiB
   */
  public static String licenseText() throws IOException {
    StringBuilder text = new StringBuilder();
    for (String name : LICENSE_FILES) {
      text.append(UiText.LICENSES_HEADING.format(name)).append("\n\n");
      try (InputStream in = AboutInfo.class.getResourceAsStream(name)) {
        if (in == null) {
          text.append(UiText.LICENSES_MISSING.text()).append("\n\n");
          continue;
        }
        byte[] bytes = Streams.readLimited(in, MAX_LICENSE_BYTES);
        text.append(new String(bytes, StandardCharsets.UTF_8)).append("\n\n");
      }
    }
    return text.toString();
  }
}
