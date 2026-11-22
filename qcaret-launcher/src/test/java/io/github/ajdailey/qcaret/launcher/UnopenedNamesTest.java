package io.github.ajdailey.qcaret.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.cli.FileRequest;
import io.github.ajdailey.qcaret.app.cli.SessionMode;
import io.github.ajdailey.qcaret.app.cli.StartupRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnopenedNamesTest {
  @TempDir Path temp;

  private StartupRequest request() {
    return new StartupRequest(
        List.of(new FileRequest(temp.resolve("zz.txt"))),
        900,
        null,
        null,
        null,
        null,
        false,
        true,
        SessionMode.SKIP,
        temp.resolve("left"),
        temp.resolve("right"));
  }

  @Test
  void anExpansionDropsEveryFileAndTheComparison() {
    StartupRequest applied = new UnopenedNames(List.of("??.txt"), List.of()).apply(request());
    assertEquals(List.of(), applied.files());
    assertNull(applied.diffLeft());
    assertNull(applied.diffRight());
    assertEquals(900, applied.width());
    assertTrue(applied.newWindow());
    assertEquals(SessionMode.SKIP, applied.session());
  }

  @Test
  void withoutAnExpansionTheRequestStands() {
    StartupRequest request = request();
    assertSame(request, new UnopenedNames(List.of(), List.of("??.txt")).apply(request));
  }

  @Test
  void theMessageNamesWhatWasNotOpened() {
    assertNull(new UnopenedNames(List.of(), List.of()).message());
    String unpassed = new UnopenedNames(List.of(), List.of("a?.txt", "b*.txt")).message();
    assertTrue(unpassed.startsWith("Not opened: a?.txt, b*.txt."), unpassed);
    String expanded = new UnopenedNames(List.of("??.txt"), List.of("c?.txt")).message();
    assertTrue(expanded.contains("Java expanded ??.txt as a wildcard"), expanded);
  }
}
