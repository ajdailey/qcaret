package io.github.ajdailey.qcaret.core.testkit;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/** Deletes directory trees that tests created. */
public final class TempTrees {
  private static final long RETRY_MILLIS = 100;

  private TempTrees() {}

  /**
   * Deletes a tree, retrying until the bound passes. On Windows a file that a terminated process
   * had open stays delete-pending for a moment after it exits, so its directory is not yet empty. A
   * symbolic link is deleted, not followed.
   *
   * @param root the tree; nothing happens when it does not exist
   * @param bound the longest time to keep retrying
   * @throws IOException the last failure once the bound passes
   * @throws InterruptedException when interrupted between attempts
   */
  public static void delete(Path root, Duration bound) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    while (true) {
      try {
        deleteOnce(root);
        return;
      } catch (IOException e) {
        if (System.nanoTime() - deadline >= 0) {
          throw e;
        }
        Thread.sleep(RETRY_MILLIS);
      }
    }
  }

  private static void deleteOnce(Path root) throws IOException {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
            if (e != null) {
              throw e;
            }
            Files.deleteIfExists(dir);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
