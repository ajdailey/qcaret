package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.view.WorkspaceNode;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.MemoryBudget;
import io.github.ajdailey.qcaret.core.io.HiddenFiles;
import io.github.ajdailey.qcaret.core.workspace.DirectoryListing;
import io.github.ajdailey.qcaret.core.workspace.FileIndex;
import io.github.ajdailey.qcaret.core.workspace.ListingOptions;
import io.github.ajdailey.qcaret.core.workspace.WalkRules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * A hidden-plus-system junction that denies listing, as {@code My Documents} in a Windows profile:
 * left out of the browser, the index and the walk rules with Hidden off, and listed as {@code
 * Access denied: <folder>}. Confirms that {@link Files#isHidden} follows the junction to its
 * target.
 */
@EnabledOnOs(OS.WINDOWS)
class WindowsJunctionTest {
  /** Everyone, as the profile junctions' deny entry names it. */
  private static final String EVERYONE = "*S-1-1-0";

  @TempDir Path temp;

  private Harness harness;
  private Path root;
  private Path target;
  private Path link;

  @BeforeEach
  void create() throws Exception {
    harness = new Harness(temp);
    root = Files.createDirectory(temp.resolve("ws")).toRealPath();
    target = Files.createDirectory(root.resolve("folder"));
    Files.writeString(target.resolve("inside.txt"), "inside\n");
    link = root.resolve("link");
  }

  @AfterEach
  void close() throws InterruptedException {
    harness.close();
  }

  /** Runs a command; returns its exit code, with its output in {@code out}. */
  private static int run(StringBuilder out, String... command)
      throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try (InputStream in = process.getInputStream()) {
      out.append(new String(in.readAllBytes(), Charset.defaultCharset()));
    }
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), String.join(" ", command));
    return process.exitValue();
  }

  /** A junction with the hidden and system attributes; closing removes the junction only. */
  private static final class Junction implements AutoCloseable {
    private final Path path;
    private boolean denied;

    Junction(Path path, Path target) throws Exception {
      this.path = path;
      StringBuilder out = new StringBuilder();
      int code = run(out, "cmd", "/c", "mklink", "/J", path.toString(), target.toString());
      assertEquals(0, code, out.toString());
      Files.setAttribute(path, "dos:hidden", true, LinkOption.NOFOLLOW_LINKS);
      Files.setAttribute(path, "dos:system", true, LinkOption.NOFOLLOW_LINKS);
    }

    /** Adds a deny-list entry for Everyone on the junction itself; false when icacls fails. */
    boolean deny(StringBuilder out) throws Exception {
      denied = run(out, "icacls", path.toString(), "/deny", EVERYONE + ":(RD)", "/L") == 0;
      return denied;
    }

    @Override
    public void close() throws Exception {
      StringBuilder out = new StringBuilder();
      if (denied) {
        run(out, "icacls", path.toString(), "/remove:d", EVERYONE, "/L");
      }
      assertEquals(0, run(out, "cmd", "/c", "rmdir", path.toString()), out.toString());
    }
  }

  private WorkspaceController workspace() {
    return harness.application.workspace();
  }

  private void showBrowser() throws InterruptedException {
    workspace().set(root);
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            return root.equals(workspace().root()) && workspace().index() != null;
          }
        },
        "workspace set and indexed");
    harness.application.execute(CommandId.WORKSPACE_BROWSER);
  }

  private void shows(final WorkspaceNode directory, final List<String> expected)
      throws InterruptedException {
    harness.await(
        new BooleanSupplier() {
          @Override
          public boolean getAsBoolean() {
            List<String> names = new ArrayList<String>();
            for (WorkspaceNode node : workspace().children(directory)) {
              names.add(node.name());
            }
            return expected.equals(names);
          }
        },
        directory.name() + " shows " + expected);
  }

  private static List<String> indexed(Path folder) {
    FileIndex index =
        FileIndex.build(
            folder,
            new WalkRules(Set.of()),
            FileIndex.MAX_FILES,
            new MemoryBudget(1L << 30, 60),
            new CancellationToken());
    List<String> paths = new ArrayList<String>();
    for (int i = 0; i < index.size(); i++) {
      paths.add(index.path(i));
    }
    return paths;
  }

  @Test
  void aHiddenJunctionStaysOutWithHiddenOff() throws Exception {
    try (Junction junction = new Junction(link, target)) {
      DosFileAttributes own =
          Files.readAttributes(link, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      assertTrue(own.isHidden() && own.isSystem(), "the junction is hidden and system");
      assertFalse(Files.isHidden(link), "Files.isHidden reads the target's attributes");

      assertTrue(HiddenFiles.hidden(link));
      assertTrue(new WalkRules(Set.of()).skips(link, true));
      assertEquals(List.of("folder/inside.txt"), indexed(root));
      ListingOptions shown =
          new ListingOptions(
              ListingOptions.DEFAULTS.filter(),
              true,
              ListingOptions.DEFAULTS.order(),
              ListingOptions.DEFAULTS.descending());
      List<String> names = new ArrayList<String>();
      for (DirectoryListing.Entry entry :
          DirectoryListing.read(root, new WalkRules(Set.of()), shown, new CancellationToken())
              .entries()) {
        names.add(entry.name());
      }
      assertEquals(List.of("folder", "link"), names);

      showBrowser();
      shows(workspace().rootNode(), List.of("folder"));
      workspace().browse().setHiddenShown(true);
      shows(workspace().rootNode(), List.of("folder", "link"));
    }
  }

  @Test
  void aDeniedJunctionListsAsAccessDenied() throws Exception {
    try (Junction junction = new Junction(link, target)) {
      StringBuilder out = new StringBuilder();
      Assumptions.assumeTrue(junction.deny(out), "icacls cannot add the entry: " + out);
      assertThrows(
          AccessDeniedException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              DirectoryListing.read(link, new WalkRules(Set.of()), new CancellationToken());
            }
          });
      assertTrue(HiddenFiles.hidden(link), "the attributes read past the deny entry");

      showBrowser();
      workspace().browse().setHiddenShown(true);
      shows(workspace().rootNode(), List.of("folder", "link"));
      WorkspaceNode node = new WorkspaceNode(link, "link", WorkspaceNode.Kind.DIRECTORY);
      workspace().expand(node);
      shows(node, List.of("Access denied: link"));
    }
  }
}
