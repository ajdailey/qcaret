package io.github.ajdailey.qcaret.core.text;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.util.Streams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the EditorConfig properties of a file: reads {@code .editorconfig} in the file's directory
 * and each parent up to one with {@code root = true} or the file system root, then applies the
 * matching sections, farthest file first and in file order, so a later section and a nearer file
 * win. A value of {@code unset} removes the property. Then the defaults of the EditorConfig core
 * libraries apply: {@code indent_style = tab} without {@code indent_size} gives {@code indent_size
 * = tab}; {@code indent_size = tab} takes {@code tab_width} when one is set; a numeric {@code
 * indent_size} without {@code tab_width} gives that {@code tab_width}.
 *
 * <p>The directories are the path's parents as named, so a link in the path is not resolved; the
 * caller passes a real path. A {@code .editorconfig} is read when it resolves to a regular file,
 * through a symbolic link too; a directory, FIFO or device is skipped unread. A file over {@value
 * #MAX_BYTES} bytes or {@value #MAX_LINES} lines, not valid UTF-8, unreadable or malformed ({@link
 * EditorConfigFile#parse}) contributes nothing, its {@code root} included, and is listed in {@link
 * Result#problems}.
 */
public final class EditorConfig {
  /** Name of the files read. */
  public static final String FILE_NAME = ".editorconfig";

  /** Largest file read, in bytes. */
  public static final int MAX_BYTES = 256 * 1024;

  /** Most lines in a file read. */
  public static final int MAX_LINES = 10_000;

  private EditorConfig() {}

  /**
   * The properties found for a file.
   *
   * @param properties property names to values, lower-cased where {@link EditorConfigFile} says
   * @param files the files with a section matching the path, nearest first
   * @param problems one line per file that contributed nothing, naming it and why
   */
  public record Result(Map<String, String> properties, List<Path> files, List<String> problems) {
    /** No file found. */
    public static final Result NONE =
        new Result(Collections.<String, String>emptyMap(), List.of(), List.of());
  }

  /**
   * Finds a file's properties.
   *
   * @param file the file's absolute real path; it need not exist
   * @return the properties, the contributing files and the problems
   */
  @WorkerThread
  public static Result resolve(Path file) {
    Path absolute = file.toAbsolutePath().normalize();
    List<Path> directories = new ArrayList<Path>();
    List<EditorConfigFile> parsed = new ArrayList<EditorConfigFile>();
    List<String> problems = new ArrayList<String>();
    for (Path dir = absolute.getParent(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(FILE_NAME);
      if (!Files.isRegularFile(candidate)) {
        continue;
      }
      EditorConfigFile config;
      try {
        config = EditorConfigFile.parse(read(candidate));
      } catch (IOException | EditorConfigFile.MalformedException e) {
        problems.add(candidate + ": " + e.getMessage());
        continue;
      }
      directories.add(dir);
      parsed.add(config);
      if (config.root()) {
        break;
      }
    }
    Map<String, String> properties = new LinkedHashMap<String, String>();
    List<Path> files = new ArrayList<Path>();
    for (int i = parsed.size() - 1; i >= 0; i--) {
      String path = relative(directories.get(i), absolute);
      boolean matched = false;
      for (EditorConfigFile.Section section : parsed.get(i).sections()) {
        if (section.glob().matches(path)) {
          matched = true;
          properties.putAll(section.properties());
        }
      }
      if (matched) {
        files.add(0, directories.get(i).resolve(FILE_NAME));
      }
    }
    Iterator<String> values = properties.values().iterator();
    while (values.hasNext()) {
      if (EditorConfigFile.lower(values.next()).equals("unset")) {
        values.remove();
      }
    }
    defaults(properties);
    return new Result(properties, files, problems);
  }

  /**
   * Applies the core libraries' defaults to resolved properties.
   *
   * @param p the properties, changed in place
   */
  static void defaults(Map<String, String> p) {
    if ("tab".equals(p.get("indent_style")) && !p.containsKey("indent_size")) {
      p.put("indent_size", "tab");
    }
    String size = p.get("indent_size");
    if (size != null && !size.equals("tab") && !p.containsKey("tab_width")) {
      p.put("tab_width", size);
    }
    if ("tab".equals(p.get("indent_size")) && p.containsKey("tab_width")) {
      p.put("indent_size", p.get("tab_width"));
    }
  }

  /** Returns a path below a directory as {@code /name/name}. */
  private static String relative(Path dir, Path file) {
    StringBuilder out = new StringBuilder();
    for (Path name : dir.relativize(file)) {
      out.append('/').append(name);
    }
    return out.toString();
  }

  private static String read(Path file) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = Streams.readLimited(in, MAX_BYTES);
    }
    String text;
    try {
      text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("Not UTF-8", e);
    }
    int lines = 1;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '\n' || (c == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n'))) {
        lines++;
      }
    }
    if (lines > MAX_LINES) {
      throw new IOException("Over " + MAX_LINES + " lines");
    }
    return text;
  }
}
