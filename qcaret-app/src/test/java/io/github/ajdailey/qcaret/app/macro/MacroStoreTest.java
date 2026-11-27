package io.github.ajdailey.qcaret.app.macro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The macro folder: write and list round-trip, unreadable files are listed with a reason, names are
 * checked on write, a write leaves no temporary file, and every file stays under the settings
 * folder. The asynchronous operations report through the UI executor.
 */
class MacroStoreTest {
  @TempDir Path temp;

  private final BlockingQueue<Runnable> uiQueue = new LinkedBlockingQueue<Runnable>();
  private final Executor ui =
      new Executor() {
        @Override
        public void execute(Runnable task) {
          uiQueue.add(task);
        }
      };
  private Workers workers;
  private Path settings;
  private MacroStore store;

  @BeforeEach
  void create() {
    workers =
        new Workers(
            1,
            new Thread.UncaughtExceptionHandler() {
              @Override
              public void uncaughtException(Thread thread, Throwable error) {
                throw new AssertionError(error);
              }
            });
    settings = temp.resolve("install");
    store = new MacroStore(settings, workers, ui);
  }

  @AfterEach
  void close() {
    workers.close();
  }

  private static Macro macro(String name, CommandId... commands) {
    List<MacroStep> steps = new ArrayList<MacroStep>();
    for (CommandId command : commands) {
      steps.add(new MacroStep.Command(command));
    }
    return new Macro(name, null, steps);
  }

  @Test
  void aMissingFolderListsNothing() throws IOException {
    assertEquals(settings.resolve(MacroStore.DIRECTORY), store.directory());
    assertEquals(List.of(), store.readAll());
  }

  @Test
  void writtenMacrosListByNameIgnoringCase() throws IOException {
    Macro b = new Macro("beta", KeyStroke.parse("F2"), macro("beta", CommandId.UNDO).steps());
    Macro a = macro("Alpha", CommandId.CARET_LEFT, CommandId.NEWLINE);
    store.write(b);
    store.write(a);
    List<MacroStore.Entry> entries = store.readAll();
    assertEquals(
        List.of(new MacroStore.Entry("Alpha", a, null), new MacroStore.Entry("beta", b, null)),
        entries);
    assertEquals(2, files(store.directory()).size());
  }

  @Test
  void aWriteReplacesTheFileAndLeavesNoTemporaryFile() throws IOException {
    store.write(macro("m", CommandId.CARET_LEFT));
    store.write(macro("m", CommandId.CARET_RIGHT));
    assertEquals(macro("m", CommandId.CARET_RIGHT), store.readAll().get(0).macro());
    assertEquals(List.of(store.directory().resolve("m" + MacroStore.SUFFIX)), files(temp));
  }

  @Test
  void unreadableFilesAreListedWithAReason() throws IOException {
    Path dir = Files.createDirectories(store.directory());
    Files.writeString(dir.resolve("bad text" + MacroStore.SUFFIX), "qcaret-macro 1\njump 3\n");
    Files.write(dir.resolve("binary" + MacroStore.SUFFIX), new byte[] {(byte) 0xff, 0x0a});
    byte[] big = new byte[MacroStore.MAX_BYTES + 1];
    Arrays.fill(big, (byte) '#');
    Files.write(dir.resolve("big" + MacroStore.SUFFIX), big);
    Files.writeString(dir.resolve("a.b" + MacroStore.SUFFIX), "qcaret-macro 1\ncommand UNDO\n");
    Files.writeString(dir.resolve("other.txt"), "qcaret-macro 1\ncommand UNDO\n");
    store.write(macro("Wrap", CommandId.UNDO));
    boolean caseClash = !Files.exists(dir.resolve("wrap" + MacroStore.SUFFIX));
    if (caseClash) {
      Files.writeString(dir.resolve("wrap" + MacroStore.SUFFIX), "qcaret-macro 1\ncommand UNDO\n");
    }
    List<MacroStore.Entry> entries = store.readAll();
    List<String> unreadable = new ArrayList<String>();
    for (MacroStore.Entry entry : entries) {
      assertEquals(entry.macro() == null, entry.problem() != null, entry.name());
      if (entry.macro() == null) {
        unreadable.add(entry.name());
      }
    }
    List<String> expected = new ArrayList<String>(List.of("a.b", "bad text", "big", "binary"));
    if (caseClash) {
      expected.add("wrap");
    }
    assertEquals(expected, unreadable);
    assertEquals(expected.size() + 1, entries.size());
  }

  @Test
  void writeRefusesNamesThatAreNotMacroNames() {
    for (final String name : new String[] {"CON", "a/b", " x", "x".repeat(65)}) {
      assertThrows(
          IOException.class,
          new Executable() {
            @Override
            public void execute() throws Throwable {
              store.write(macro(name, CommandId.UNDO));
            }
          },
          name);
    }
    assertFalse(Files.exists(store.directory().resolve("CON" + MacroStore.SUFFIX)));
  }

  @Test
  void renameAndDeleteMoveAndRemoveTheFile() throws IOException {
    store.write(macro("one", CommandId.UNDO));
    store.move("one", "two");
    assertEquals("two", store.readAll().get(0).name());
    assertThrows(
        NoSuchFileException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            store.move("one", "three");
          }
        });
    store.remove("two");
    store.remove("two");
    assertEquals(List.of(), store.readAll());
  }

  @Test
  void everyFileStaysUnderTheSettingsFolder() throws IOException {
    store.write(macro("kept", CommandId.UNDO));
    List<Path> outside = new ArrayList<Path>();
    for (Path file : files(temp)) {
      if (!file.startsWith(store.directory())) {
        outside.add(file);
      }
    }
    assertEquals(List.of(), outside);
  }

  @Test
  void theAsynchronousOperationsReportOnTheUiExecutor() throws Exception {
    final AtomicReference<IOException> saved = new AtomicReference<IOException>();
    final AtomicReference<List<MacroStore.Entry>> listed =
        new AtomicReference<List<MacroStore.Entry>>();
    store.save(
        macro("async", CommandId.UNDO),
        new MacroStore.Done() {
          @Override
          public void done(IOException failure) {
            saved.set(failure == null ? new IOException("none") : failure);
          }
        });
    runOneUiTask();
    assertEquals("none", saved.get().getMessage());
    store.list(
        new Consumer<List<MacroStore.Entry>>() {
          @Override
          public void accept(List<MacroStore.Entry> entries) {
            listed.set(entries);
          }
        });
    runOneUiTask();
    assertEquals("async", listed.get().get(0).name());
    store.delete(
        "missing",
        new MacroStore.Done() {
          @Override
          public void done(IOException failure) {
            saved.set(failure);
          }
        });
    runOneUiTask();
    assertNull(saved.get());
    store.rename(
        "missing",
        "other",
        new MacroStore.Done() {
          @Override
          public void done(IOException failure) {
            saved.set(failure);
          }
        });
    runOneUiTask();
    assertNotNull(saved.get());
    assertTrue(Files.exists(store.directory().resolve("async" + MacroStore.SUFFIX)));
  }

  /** Returns the regular files under a folder, at any depth. */
  private static List<Path> files(Path dir) throws IOException {
    List<Path> out = new ArrayList<Path>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path path : stream) {
        if (Files.isDirectory(path)) {
          out.addAll(files(path));
        } else {
          out.add(path);
        }
      }
    }
    return out;
  }

  private void runOneUiTask() throws InterruptedException {
    Runnable task = uiQueue.poll(20, TimeUnit.SECONDS);
    assertNotNull(task, "UI task");
    task.run();
  }
}
