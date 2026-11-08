package io.github.ajdailey.qcaret.core.workspace;

import io.github.ajdailey.qcaret.core.io.HiddenFiles;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * What the workspace browser and the file index leave out: hidden files and directories ({@link
 * HiddenFiles}) and directories with an excluded name, with their trees. Immutable.
 *
 * @param excludedDirectories directory names skipped with their trees
 */
public record WalkRules(Set<String> excludedDirectories) {
  /** Copies the names. */
  public WalkRules {
    excludedDirectories = Set.copyOf(excludedDirectories);
  }

  /**
   * Parses a {@code ;}-separated list of directory names.
   *
   * @param names for example {@code .git;.svn;.hg}
   * @return the rules
   */
  public static WalkRules of(String names) {
    Set<String> set = new HashSet<String>();
    for (String part : names.split(";", -1)) {
      String name = part.strip();
      if (!name.isEmpty()) {
        set.add(name);
      }
    }
    return new WalkRules(set);
  }

  /**
   * Reports whether an entry is left out. Reads the hidden attribute from disk on Windows.
   *
   * @param path the entry
   * @param directory true for a directory
   * @return true for a hidden entry or an excluded directory name
   */
  public boolean skips(Path path, boolean directory) {
    if (path.getFileName() == null) {
      return false;
    }
    return excludes(path, directory) || HiddenFiles.hidden(path);
  }

  /**
   * Reports whether an entry is an excluded directory, whether hidden or not.
   *
   * @param path the entry
   * @param directory true for a directory
   * @return true for a directory with an excluded name
   */
  public boolean excludes(Path path, boolean directory) {
    Path name = path.getFileName();
    return directory && name != null && excludedDirectories.contains(name.toString());
  }
}
