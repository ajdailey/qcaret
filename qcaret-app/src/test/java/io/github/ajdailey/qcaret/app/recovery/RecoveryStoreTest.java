package io.github.ajdailey.qcaret.app.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.UndoLimits;
import io.github.ajdailey.qcaret.core.io.Digests;
import io.github.ajdailey.qcaret.core.text.LineEnding;
import io.github.ajdailey.qcaret.core.text.TextEncoding;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

  /** Reading a POSIX-locked file and closing it drops the lock; {@code owner.lock} must survive. */
  @Test
  void aLiveInstanceWhoseLockFileWasReadIsNotClaimed() throws Exception {
    Process child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                OwnerMain.class.getName(),
                temp.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try (BufferedReader out =
        new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      assertEquals(OwnerMain.READY, out.readLine());
      RecoveryStore other = new RecoveryStore(temp, "other");
      other.open();
      assertTrue(other.claimDead(0).isEmpty());
      other.close(false);
      assertTrue(Files.exists(temp.resolve(RecoveryStore.DIRECTORY).resolve(OwnerMain.INSTANCE)));
    } finally {
      child.destroyForcibly();
      assertTrue(child.waitFor(60, TimeUnit.SECONDS));
    }
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
  void anUnreadableOriginalHidesNoBuffer() throws IOException {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
    byte[] bytes = "base\n".getBytes(StandardCharsets.UTF_8);
    Path file = Files.write(temp.resolve("a.txt"), bytes);
    RecoveryStore dead = new RecoveryStore(temp, "dead");
    dead.open();
    dead.write(header("b1", file.toString(), 5, Digests.of(bytes, 5)), text("edited"));
    dead.write(header("b2", "", -1, ""), text("untitled"));
    dead.close(true);
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    RecoveryStore own = own();
    try {
      assumeFalse(Files.isReadable(file), "running as root");
      List<RecoveryStore.Claim> claims = own.claimDead(0);
      assertEquals(1, claims.size());
      List<RecoveryStore.Entry> entries = claims.get(0).entries();
      assertEquals(2, entries.size());
      assertEquals(RecoveryStore.Original.UNREADABLE, entries.get(0).original());
      assertEquals(RecoveryStore.Original.NONE, entries.get(1).original());
      claims.get(0).close();
    } finally {
      own.close(false);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void anUnreadableRecoveryFileHidesNoOtherFile() throws IOException {
    assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
    RecoveryStore dead = new RecoveryStore(temp, "dead");
    dead.open();
    dead.write(header("b1", "", -1, ""), text("one"));
    dead.write(header("b2", "", -1, ""), text("two"));
    dead.close(true);
    Path file = temp.resolve(RecoveryStore.DIRECTORY).resolve("dead").resolve("b1.qcr");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
    RecoveryStore own = own();
    try {
      assumeFalse(Files.isReadable(file), "running as root");
      List<RecoveryStore.Claim> claims = own.claimDead(0);
      assertEquals(1, claims.size());
      List<RecoveryStore.Entry> entries = claims.get(0).entries();
      assertEquals(2, entries.size());
      assertTrue(entries.get(0).unreadable());
      assertNull(entries.get(0).header());
      assertFalse(entries.get(1).unreadable());
      assertEquals("b2", entries.get(1).header().id());
      claims.get(0).close();
      assertTrue(Files.exists(file));
    } finally {
      own.close(false);
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }
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

  @Test
  void movedSnapshotsAreClaimedAsADeadInstance() throws IOException {
    assertEquals(0, RecoveryStore.moveSnapshotsToRecovery(temp, "moved"));
    RecoveryStore writer = own();
    writer.writeSnapshot(header("b1", "", -1, ""), text("one"));
    writer.writeSnapshot(header("b2", "", -1, ""), text("two"));
    writer.close(false);
    assertEquals(2, RecoveryStore.moveSnapshotsToRecovery(temp, "moved"));
    assertFalse(Files.exists(RecoveryStore.snapshotFile(temp, "b1")));
    RecoveryStore store = own();
    List<RecoveryStore.Claim> claims = store.claimDead(0);
    assertEquals(1, claims.size());
    assertEquals(2, claims.get(0).entries().size());
    assertEquals("b1", claims.get(0).entries().get(0).header().id());
    claims.get(0).close();
    store.close(false);
  }
}
