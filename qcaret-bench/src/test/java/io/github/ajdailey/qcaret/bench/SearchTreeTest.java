package io.github.ajdailey.qcaret.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.search.FileSearch;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The rare word sits where {@link SearchTree} says, and the tree is deleted. */
class SearchTreeTest {
  private static int files(SearchTree tree, SearchTree.Query query, Workers workers) {
    final AtomicInteger files = new AtomicInteger();
    FileSearch.run(
        workers,
        tree.root(),
        true,
        Set.<Path>of(),
        tree.options(query, null),
        new SearchHarness.CountingSink() {
          @Override
          public void found(FileSearch.FileHits hits) {
            files.incrementAndGet();
          }
        },
        new CancellationToken());
    return files.get();
  }

  @Test
  void theRareWordIsInEveryHundredthFileAndEachLargeFile() throws IOException {
    Path root;
    try (SearchTree tree = SearchTree.create(250, 2, 100_000);
        Workers workers = SearchHarness.workers()) {
      root = tree.root();
      assertEquals(5, files(tree, SearchTree.Query.LITERAL_FEW, workers));
      assertEquals(5, files(tree, SearchTree.Query.REGEX_FEW, workers));
      assertTrue(files(tree, SearchTree.Query.LITERAL_MANY, workers) > 200);
    }
    assertFalse(Files.exists(root));
  }
}
