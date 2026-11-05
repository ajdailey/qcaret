package io.github.ajdailey.qcaret.core.search;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which files and directories a directory search visits. A mask list holds {@code ;}-separated
 * globs matched against a file's name, such as {@code *.java;*.{xml,xsd}}; an empty include list
 * takes every file. An excluded directory name skips that directory's whole tree. Hidden files and
 * directories (a name starting with {@code .}, or the platform's hidden attribute, which the walker
 * reads) are skipped unless {@code hidden} is on. Immutable.
 */
public final class FileFilter {
  /** Directory names a search skips unless the user sets others. */
  public static final String DEFAULT_EXCLUDED_DIRECTORIES = ".git;.svn;.hg";

  private final List<PathMatcher> include;
  private final List<PathMatcher> exclude;
  private final Set<String> excludedDirectories;
  private final boolean hidden;

  private FileFilter(
      List<PathMatcher> include,
      List<PathMatcher> exclude,
      Set<String> excludedDirectories,
      boolean hidden) {
    this.include = include;
    this.exclude = exclude;
    this.excludedDirectories = excludedDirectories;
    this.hidden = hidden;
  }

  /**
   * Parses the masks.
   *
   * @param include globs a file name must match one of; blank for every file
   * @param exclude globs no file name may match
   * @param excludedDirectories directory names, {@code ;}-separated
   * @param hidden true to visit hidden files and directories
   * @return the filter
   * @throws IllegalArgumentException for a glob that does not parse
   */
  public static FileFilter of(
      String include, String exclude, String excludedDirectories, boolean hidden) {
    Set<String> names = new HashSet<String>();
    for (String name : split(excludedDirectories)) {
      names.add(name);
    }
    return new FileFilter(globs(include), globs(exclude), names, hidden);
  }

  private static List<String> split(String list) {
    List<String> out = new ArrayList<String>();
    for (String part : list.split(";", -1)) {
      String trimmed = part.strip();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  private static List<PathMatcher> globs(String list) {
    List<PathMatcher> out = new ArrayList<PathMatcher>();
    for (String glob : split(list)) {
      out.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
    }
    return out;
  }

  /**
   * Reports whether hidden files and directories are visited.
   *
   * @return true when they are
   */
  public boolean hidden() {
    return hidden;
  }

  /**
   * Reports whether a file name passes the masks.
   *
   * @param name the file's name, one path element
   * @return true when an include glob matches, or none is given, and no exclude glob matches
   */
  public boolean acceptsFile(Path name) {
    boolean included = include.isEmpty();
    for (PathMatcher m : include) {
      if (m.matches(name)) {
        included = true;
        break;
      }
    }
    if (!included) {
      return false;
    }
    for (PathMatcher m : exclude) {
      if (m.matches(name)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Reports whether a directory's tree is visited.
   *
   * @param name the directory's name, one path element
   * @return false for an excluded directory name
   */
  public boolean acceptsDirectory(Path name) {
    return !excludedDirectories.contains(name.toString());
  }
}
