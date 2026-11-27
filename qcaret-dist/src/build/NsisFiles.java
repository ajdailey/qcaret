import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes an NSIS include defining {@code QCARET_INSTALL_FILES}, a {@code File} command for every
 * file under a directory, and {@code QCARET_UNINSTALL_FILES}, a {@code Delete} for each of those
 * files and an {@code RMDir} for each directory, deepest first. Entries are sorted by path, so the
 * installer does not depend on the order the host file system lists a directory. Run at build time
 * with the source launcher: {@code java NsisFiles.java <dir> <out.nsh>}.
 */
public final class NsisFiles {
  private NsisFiles() {}

  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: NsisFiles <dir> <out.nsh>");
    }
    Path root = Path.of(args[0]).toAbsolutePath();
    List<String> dirs = new ArrayList<>();
    List<String> files = new ArrayList<>();
    collect(root, "", dirs, files);
    Collections.sort(dirs);
    Collections.sort(files);

    StringBuilder out = new StringBuilder();
    out.append("!macro QCARET_INSTALL_FILES\n");
    String current = null;
    for (String file : files) {
      int slash = file.lastIndexOf('/');
      String dir = slash < 0 ? "" : file.substring(0, slash);
      if (!dir.equals(current)) {
        out.append("  SetOutPath \"").append(target(dir)).append("\"\n");
        current = dir;
      }
      out.append("  File \"").append(escape(root.resolve(file).toString())).append("\"\n");
    }
    out.append("  SetOutPath \"$INSTDIR\"\n");
    out.append("!macroend\n\n");

    out.append("!macro QCARET_UNINSTALL_FILES\n");
    for (String file : files) {
      out.append("  Delete \"").append(target(file)).append("\"\n");
    }
    for (int i = dirs.size() - 1; i >= 0; i--) {
      out.append("  RMDir \"").append(target(dirs.get(i))).append("\"\n");
    }
    out.append("!macroend\n");
    Files.writeString(Path.of(args[1]), out.toString(), StandardCharsets.UTF_8);
  }

  private static void collect(Path root, String prefix, List<String> dirs, List<String> files)
      throws IOException {
    try (DirectoryStream<Path> entries = Files.newDirectoryStream(root.resolve(prefix))) {
      for (Path entry : entries) {
        String name = prefix + entry.getFileName();
        if (Files.isDirectory(entry)) {
          dirs.add(name);
          collect(root, name + "/", dirs, files);
        } else {
          files.add(name);
        }
      }
    }
  }

  /** The installed path of {@code relative}, a {@code /}-separated path under the root. */
  private static String target(String relative) {
    if (relative.isEmpty()) {
      return "$INSTDIR";
    }
    return "$INSTDIR\\" + escape(relative.replace('/', '\\'));
  }

  /** Escapes NSIS's variable sigil; the names hold no quote. */
  private static String escape(String text) {
    return text.replace("$", "$$");
  }
}
