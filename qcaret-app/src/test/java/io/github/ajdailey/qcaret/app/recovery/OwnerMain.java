package io.github.ajdailey.qcaret.app.recovery;

import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A child process for {@code RecoveryStoreTest}: a live instance with a recovery file. It reads its
 * own {@code owner.lock}, says so and waits to die.
 */
public final class OwnerMain {
  static final String READY = "read";
  static final String INSTANCE = "live";

  private OwnerMain() {}

  /**
   * Opens the store and blocks.
   *
   * @param args the settings directory
   * @throws IOException when the store fails
   * @throws InterruptedException never in practice; the test kills the process
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    Path settings = Path.of(args[0]);
    RecoveryStore store = new RecoveryStore(settings, INSTANCE);
    store.open();
    store.write(
        new RecoveryFile.Header(
            "b1",
            "",
            "Untitled 1",
            "2026-11-05T10:00:00Z",
            "2026-11-05T10:00:30Z",
            1,
            "UTF-8",
            false,
            "LF",
            -1,
            "2026-11-05T09:00:00Z",
            "",
            "",
            "0,0",
            ""),
        Document.of(
                "mock",
                TextEncoding.of(StandardCharsets.UTF_8, false),
                LineEnding.LF,
                UndoLimits.DEFAULTS)
            .text());
    try {
      Files.readAllBytes(
          settings.resolve(RecoveryStore.DIRECTORY).resolve(INSTANCE).resolve(RecoveryStore.LOCK));
    } catch (IOException e) {
      // Windows refuses the locked region of a held file.
      System.err.println(e);
    }
    System.out.println(READY);
    System.out.flush();
    Thread.sleep(Long.MAX_VALUE);
  }
}
