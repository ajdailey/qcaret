package io.github.ajdailey.qcaret.app.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Instance directories: the lock decides liveness, and old and damaged files are handled. */
class RecoveryStoreTest {
  @TempDir Path temp;

  private static TextSnapshot text(String value) {
    return Document.of(
            value,
            TextEncoding.of(StandardCharsets.UTF_8, false),
            LineEnding.LF,
            UndoLimits.DEFAULTS)
        .text();
  }

  private static RecoveryFile.Header header(String id, String path, long size, String digest) {
    return new RecoveryFile.Header(
        id,
        path,
        "Untitled 1",
        "2026-11-05T10:00:00Z",
        "2026-11-05T10:00:30Z",
        1,
        "UTF-8",
        false,
        "LF",
        size,
        "2026-11-05T09:00:00Z",
        digest,
        "",
        "0,0",
        "");
  }

  private Path dead(String instance) throws IOException {
    RecoveryStore store = new RecoveryStore(temp, instance);
    store.open();
    store.write(header("b1", "", -1, ""), text("mock"));
    store.close(true);
    return temp.resolve(RecoveryStore.DIRECTORY).resolve(instance);
  }

  private RecoveryStore own() throws IOException {
    RecoveryStore store = new RecoveryStore(temp, "own");
    store.open();
    return store;
  }

  @Test
  void aKeptDirectoryIsClaimedAndARunningInstanceIsNot() throws IOException {
    Path directory = dead("dead");
    RecoveryStore own = own();
    List<RecoveryStore.Claim> claims = own.claimDead(0);
    assertEquals(1, claims.size());
    RecoveryStore.Entry entry = claims.get(0).entries().get(0);
    assertEquals(directory.resolve("b1.qcr"), entry.file());
    assertFalse(entry.damaged());
    assertEquals(RecoveryStore.Original.NONE, entry.original());
    claims.get(0).close();
    assertTrue(Files.exists(entry.file()), "a claim keeps what no one decided");
    own.close(false);
  }

  @Test
  void aDirectoryWithoutFilesGoes() throws IOException {
    Path directory = dead("dead");
    Files.delete(directory.resolve("b1.qcr"));
    RecoveryStore own = own();
    assertEquals(List.of(), own.claimDead(0));
    assertFalse(Files.exists(directory));
    own.close(false);
  }

  @Test
  void filesPastTheLimitAreDeleted() throws IOException {
    Path directory = dead("dead");
    Files.setLastModifiedTime(directory.resolve("b1.qcr"), FileTime.fromMillis(1000));
    RecoveryStore own = own();
    assertEquals(List.of(), own.claimDead(2000));
    assertFalse(Files.exists(directory));
    own.close(false);
  }

  @Test
  void aDamagedFileIsListedAndKept() throws IOException {
    Path directory = dead("dead");
    Path file = directory.resolve("b1.qcr");
    byte[] bytes = Files.readAllBytes(file);
    bytes[bytes.length - 71] ^= 1;
    Files.write(file, bytes);
    RecoveryStore own = own();
    List<RecoveryStore.Claim> claims = own.claimDead(0);
    RecoveryStore.Entry entry = claims.get(0).entries().get(0);
    assertTrue(entry.damaged());
    assertEquals("b1", entry.header().id());
    claims.get(0).close();
    assertTrue(Files.exists(file));
    own.close(false);
  }

  @Test
  void anUnreadableHeaderIsListedWithoutOne() throws IOException {
    Path directory = dead("dead");
    Files.writeString(directory.resolve("b1.qcr"), "mock");
    RecoveryStore own = own();
    List<RecoveryStore.Claim> claims = own.claimDead(0);
    RecoveryStore.Entry entry = claims.get(0).entries().get(0);
    assertTrue(entry.damaged());
    assertNull(entry.header());
    claims.get(0).close();
    own.close(false);
  }

  private RecoveryStore.Original original(Path file, long size, String digest) throws IOException {
    RecoveryStore writer = new RecoveryStore(temp, "writer");
    writer.open();
    writer.write(header("b1", file.toString(), size, digest), text("mock"));
    writer.close(true);
    RecoveryStore own = own();
    List<RecoveryStore.Claim> claims = own.claimDead(0);
    RecoveryStore.Original original = claims.get(0).entries().get(0).original();
    RecoveryStore.deleteFile(claims.get(0).entries().get(0).file());
    claims.get(0).close();
    own.close(false);
    return original;
  }

  @Test
  void theOriginalIsComparedWithTheBase() throws IOException {
    byte[] bytes = "mock base".getBytes(StandardCharsets.UTF_8);
    Path file = Files.write(temp.resolve("a.txt"), bytes);
    String digest = Digests.of(bytes, bytes.length);
    assertEquals(RecoveryStore.Original.UNCHANGED, original(file, bytes.length, digest));
    assertEquals(RecoveryStore.Original.CHANGED, original(file, bytes.length, "0".repeat(64)));
    assertEquals(RecoveryStore.Original.CHANGED, original(file, 1, digest));
    Files.delete(file);
    assertEquals(RecoveryStore.Original.MISSING, original(file, bytes.length, digest));
  }

  @Test
  void anOrderedCloseDeletesTheDirectoryAndAKeptCloseLeavesIt() throws IOException {
    RecoveryStore store = own();
    store.write(header("b1", "", -1, ""), text("mock"));
    Path directory = temp.resolve(RecoveryStore.DIRECTORY).resolve("own");
    assertTrue(Files.exists(directory.resolve(RecoveryStore.LOCK)));
    store.close(false);
    assertFalse(Files.exists(directory));
  }

  @Test
  void snapshotsArePruned() throws IOException {
    RecoveryStore store = own();
    store.writeSnapshot(header("b1", "", -1, ""), text("one"));
    store.writeSnapshot(header("b2", "", -1, ""), text("two"));
    store.pruneSnapshots(Set.of("b2"));
    assertFalse(Files.exists(RecoveryStore.snapshotFile(temp, "b1")));
    assertTrue(Files.exists(RecoveryStore.snapshotFile(temp, "b2")));
    store.close(false);
  }
}
