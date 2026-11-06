package io.github.ajdailey.qcaret.app.theme;

import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The colors of a theme file, {@code <settings>/themes/<name>.theme}, laid over a built-in {@link
 * Palette}. Each line is {@code light.<color> = #RRGGBB} or {@code dark.<color> = #RRGGBB}; a line
 * starting with {@code #} is a comment. {@code <color>} is a {@link Palette} color or {@code
 * token.<role>}, which replaces a {@link TokenRole}'s foreground and keeps its font style. A known
 * key with a bad value logs one WARN naming the key and keeps the built-in color; any other line is
 * ignored; the last value of a repeated key wins. Immutable.
 */
public final class ThemeColors {
  /** No file: {@link #over} returns its palette. */
  public static final ThemeColors NONE =
      new ThemeColors(Collections.<String, Rgb>emptyMap(), Collections.<String, Rgb>emptyMap());

  /** The folder of the settings directory that holds the theme files. */
  public static final String DIRECTORY = "themes";

  /** The file name ending of a theme. */
  public static final String SUFFIX = ".theme";

  /** Largest theme file read. */
  static final int MAX_FILE_BYTES = 1 << 16;

  /** Most theme files listed. */
  static final int MAX_FILES = 256;

  /** Longest theme name. */
  static final int MAX_NAME_CHARS = 64;

  private static final Logger LOG = LoggerFactory.getLogger(ThemeColors.class);
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
  private static final Pattern VALUE = Pattern.compile("#[0-9A-Fa-f]{6}");
  private static final String TOKEN = "token.";
  private static final String[] COLORS = {
    "text",
    "background",
    "selection",
    "selectionText",
    "currentLine",
    "lineNumbers",
    "gutter",
    "caret",
    "whitespace",
    "findMatch",
    "occurrence",
    "bracket",
    "tabText",
    "overviewFind",
    "overviewSearch",
    "bookmarkComment",
    "diffRemoved",
    "diffAdded",
    "diffChanged"
  };

  private final Map<String, Rgb> light;
  private final Map<String, Rgb> dark;

  private ThemeColors(Map<String, Rgb> light, Map<String, Rgb> dark) {
    this.light = light;
    this.dark = dark;
  }

  /**
   * Reports whether a string can name a theme file.
   *
   * @param name the file name without {@value #SUFFIX}, or null
   * @return false for null, an empty name, a name over {@value #MAX_NAME_CHARS} chars, one starting
   *     with {@code .}, or one holding anything but ASCII letters, digits, {@code .}, {@code _} and
   *     {@code -}
   */
  public static boolean validName(String name) {
    return name != null && name.length() <= MAX_NAME_CHARS && NAME.matcher(name).matches();
  }

  /**
   * Lists the theme files. Blocking.
   *
   * @param settings the settings directory
   * @return the names without {@value #SUFFIX}, sorted, at most {@value #MAX_FILES}; a file whose
   *     name is not {@linkplain #validName valid} is left out
   */
  public static List<String> list(Path settings) {
    List<String> names = new ArrayList<String>();
    Path folder = settings.resolve(DIRECTORY);
    if (!Files.isDirectory(folder)) {
      return names;
    }
    try (DirectoryStream<Path> listing = Files.newDirectoryStream(folder, "*" + SUFFIX)) {
      for (Path file : listing) {
        String fileName = String.valueOf(file.getFileName());
        String name = fileName.substring(0, fileName.length() - SUFFIX.length());
        if (validName(name) && names.size() < MAX_FILES) {
          names.add(name);
        }
      }
    } catch (IOException e) {
      LOG.warn("Cannot list theme files: {}", LogText.oneLine(e.getMessage()));
    }
    names.sort(null);
    return names;
  }

  /**
   * Reads a theme file. Blocking.
   *
   * @param settings the settings directory
   * @param name the theme's name; empty for none
   * @return the colors; {@link #NONE} for an empty name, and with one WARN for a file that is
   *     missing, unreadable or over {@value #MAX_FILE_BYTES} bytes
   */
  public static ThemeColors read(Path settings, String name) {
    if (name.isEmpty()) {
      return NONE;
    }
    try {
      Path file = SafePaths.child(settings.resolve(DIRECTORY), name + SUFFIX);
      if (!Files.isRegularFile(file)) {
        throw new IOException("no such file");
      }
      try (InputStream in = Files.newInputStream(file)) {
        byte[] bytes = Streams.readLimited(in, MAX_FILE_BYTES);
        return parse(new String(bytes, StandardCharsets.UTF_8), name);
      }
    } catch (IOException e) {
      LOG.warn(
          "Built-in colors apply: cannot read theme {}: {}",
          LogText.oneLine(name),
          LogText.oneLine(e.getMessage()));
      return NONE;
    }
  }

  /**
   * Parses a theme file's text.
   *
   * @param text the text
   * @param name the theme's name, for warnings
   * @return the colors
   */
  static ThemeColors parse(String text, String name) {
    Set<String> known = keys();
    Set<String> refused = new LinkedHashSet<String>();
    Map<String, Rgb> light = new HashMap<String, Rgb>();
    Map<String, Rgb> dark = new HashMap<String, Rgb>();
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      int equals = line.indexOf('=');
      if (line.startsWith("#") || equals < 0) {
        continue;
      }
      String key = line.substring(0, equals).strip();
      if (known.contains(key)) {
        Map<String, Rgb> side = key.startsWith("light.") ? light : dark;
        String color = key.substring(key.indexOf('.') + 1);
        String value = line.substring(equals + 1).strip();
        if (VALUE.matcher(value).matches()) {
          side.put(color, Rgb.of(Integer.parseInt(value.substring(1), 16)));
          refused.remove(key);
        } else {
          side.remove(color);
          refused.add(key);
        }
      }
    }
    for (String key : refused) {
      LOG.warn(
          "Built-in color kept: invalid {} in theme {}",
          LogText.oneLine(key),
          LogText.oneLine(name));
    }
    return new ThemeColors(light, dark);
  }

  /** Returns every key a theme file can set. */
  private static Set<String> keys() {
    Set<String> keys = new LinkedHashSet<String>();
    for (String side : new String[] {"light.", "dark."}) {
      for (String color : COLORS) {
        keys.add(side + color);
      }
      for (TokenRole role : TokenRole.values()) {
        keys.add(side + token(role));
      }
    }
    return keys;
  }

  private static String token(TokenRole role) {
    return TOKEN + role.name().toLowerCase(Locale.ROOT);
  }

  /**
   * Lays the colors over a palette.
   *
   * @param palette a built-in palette
   * @return the palette itself when this holds no color for its side, else a copy with the colors
   *     replaced
   */
  public Palette over(Palette palette) {
    Map<String, Rgb> colors = palette.dark() ? dark : light;
    if (colors.isEmpty()) {
      return palette;
    }
    Map<TokenRole, TokenStyle> tokens = new EnumMap<TokenRole, TokenStyle>(TokenRole.class);
    for (TokenRole role : TokenRole.values()) {
      TokenStyle style = palette.style(role);
      tokens.put(
          role,
          new TokenStyle(
              colors.getOrDefault(token(role), style.foreground()),
              style.bold(),
              style.italic(),
              style.underline()));
    }
    Palette.Accents a = palette.accents();
    return new Palette(
        palette.dark(),
        colors.getOrDefault("text", palette.text()),
        colors.getOrDefault("background", palette.background()),
        colors.getOrDefault("selection", palette.selection()),
        colors.getOrDefault("selectionText", palette.selectionText()),
        colors.getOrDefault("currentLine", palette.currentLine()),
        colors.getOrDefault("lineNumbers", palette.lineNumbers()),
        colors.getOrDefault("gutter", palette.gutter()),
        colors.getOrDefault("caret", palette.caret()),
        colors.getOrDefault("whitespace", palette.whitespace()),
        colors.getOrDefault("findMatch", palette.findMatch()),
        colors.getOrDefault("occurrence", palette.occurrence()),
        colors.getOrDefault("bracket", palette.bracket()),
        tokens,
        new Palette.Accents(
            colors.getOrDefault("tabText", a.tabText()),
            colors.getOrDefault("overviewFind", a.overviewFind()),
            colors.getOrDefault("overviewSearch", a.overviewSearch()),
            colors.getOrDefault("bookmarkComment", a.bookmarkComment()),
            colors.getOrDefault("diffRemoved", a.diffRemoved()),
            colors.getOrDefault("diffAdded", a.diffAdded()),
            colors.getOrDefault("diffChanged", a.diffChanged())));
  }
}
