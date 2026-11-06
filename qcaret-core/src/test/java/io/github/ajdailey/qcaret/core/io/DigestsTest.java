package io.github.ajdailey.qcaret.core.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** A file's streamed digest against the digest of its bytes. */
class DigestsTest {
  @TempDir Path temp;

  @Test
  void aFileLargerThanTheBlockDigestsWhole() throws Exception {
    byte[] bytes = new byte[(3 << 20) + 17];
    new Random(3).nextBytes(bytes);
    Path file = Files.write(temp.resolve("data.bin"), bytes);
    byte[] expected = Digests.sha256().digest(bytes);
    assertArrayEquals(expected, Digests.bytesOf(file, new CancellationToken()));
    assertEquals(HexFormat.of().formatHex(expected), Digests.of(file, new CancellationToken()));
  }

  @Test
  void aCancelEndsTheRead() throws Exception {
    final Path file = Files.writeString(temp.resolve("a.txt"), "text");
    final CancellationToken token = new CancellationToken();
    token.cancel();
    assertThrows(
        CancellationException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            Digests.bytesOf(file, token);
          }
        });
  }
}
