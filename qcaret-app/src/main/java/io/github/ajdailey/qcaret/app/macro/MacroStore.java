package io.github.ajdailey.qcaret.app.macro;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.text.LogText;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The saved macros: one {@value #SUFFIX} file per macro in {@value #DIRECTORY} under the settings
 * folder, named after the macro. Every operation runs on a {@code qcaret-io-} worker and reports on
 * the UI thread; nothing watches the folder, so a caller lists it again after a change. A file that
 * is over {@link #MAX_BYTES}, not UTF-8, not {@link MacroFormat} text, not a {@link MacroNames}
 * name, or a second name equal ignoring case is listed as unreadable, with a WARN once per file
 * name. A write goes to a temporary file that then replaces the target.
 */
public final class MacroStore {
  private static final Logger LOG = LoggerFactory.getLogger(MacroStore.class);

  /** The folder under the settings folder. */
  public static final String DIRECTORY = "macros";

  /** The file name suffix. */
  public static final String SUFFIX = ".qcmacro";

  /** Largest file read. */
  public static final int MAX_BYTES = 1 << 20;

  /**
   * One file of the folder.
   *
   * @param name the macro's name, the file name without {@link #SUFFIX}
   * @param macro the macro, or null when the file is unreadable
   * @param problem why the file is unreadable, or null
   */
  public record Entry(String name, Macro macro, String problem) {}

  /** Receives an operation's end on the UI thread. */
  public interface Done {
    /**
     * Called once the operation ended.
     *
     * @param failure the failure, or null when it succeeded
     */
    void done(IOException failure);
  }

  private final Path directory;
  private final Workers workers;
  private final Executor ui;
  private final Set<String> warned = ConcurrentHashMap.newKeySet();

  /**
   * Creates the store.
   *
   * @param settings the settings folder
   * @param workers runs the file operations
   * @param ui runs the callbacks on the UI thread
   */
  public MacroStore(Path settings, Workers workers, Executor ui) {
    this.directory = settings.resolve(DIRECTORY);
    this.workers = workers;
    this.ui = ui;
  }

  /**
   * Returns the folder.
   *
   * @return {@code <settings>/macros}
   */
  public Path directory() {
    return directory;
  }

  /**
   * Lists the folder, by name ignoring case; a missing folder lists nothing.
   *
   * @param listed receives the entries, or an empty list when the folder cannot be read
   */
  public void list(final Consumer<List<Entry>> listed) {
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                List<Entry> entries = List.of();
                try {
                  entries = readAll();
                } catch (IOException e) {
                  LOG.warn("Macros not listed: {}", LogText.oneLine(e));
                } finally {
                  final List<Entry> result = entries;
                  ui.execute(
                      new Runnable() {
                        @Override
                        public void run() {
                          listed.accept(result);
                        }
                      });
                }
              }
            });
    if (!started) {
      LOG.debug("Macros not listed: workers are closed");
    }
  }

  /**
   * Writes a named macro, creating the folder; a file of the same name is replaced.
   *
   * @param macro the macro, with a {@link MacroNames} name
   * @param done receives the end
   */
  public void save(final Macro macro, Done done) {
    run(
        done,
        new Operation() {
          @Override
          @WorkerThread
          public void run() throws IOException {
            write(macro);
          }
        });
  }

  /**
   * Deletes a macro's file; a missing file counts as deleted.
   *
   * @param name the macro's name
   * @param done receives the end
   */
  public void delete(final String name, Done done) {
    run(
        done,
        new Operation() {
          @Override
          @WorkerThread
          public void run() throws IOException {
            remove(name);
          }
        });
  }

  /**
   * Renames a macro's file.
   *
   * @param from the current name
   * @param to the new name, a {@link MacroNames} name no other macro has
   * @param done receives the end
   */
  public void rename(final String from, final String to, Done done) {
    run(
        done,
        new Operation() {
          @Override
          @WorkerThread
          public void run() throws IOException {
            move(from, to);
          }
        });
  }

  /** A file operation. */
  private interface Operation {
    @WorkerThread
    void run() throws IOException;
  }

  private void run(final Done done, final Operation operation) {
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                IOException failure = new IOException("macro operation failed");
                try {
                  operation.run();
                  failure = null;
                } catch (IOException e) {
                  failure = e;
                } finally {
                  final IOException result = failure;
                  ui.execute(
                      new Runnable() {
                        @Override
                        public void run() {
                          done.done(result);
                        }
                      });
                }
              }
            });
    if (!started) {
      LOG.debug("Macro operation dropped: workers are closed");
    }
  }

  /**
   * Reads every file of the folder.
   *
   * @return the entries by name ignoring case; empty for a missing folder
   * @throws IOException when the folder cannot be listed
   */
  @WorkerThread
  List<Entry> readAll() throws IOException {
    List<String> fileNames = new ArrayList<String>();
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
      for (Path file : stream) {
        Path fileName = file.getFileName();
        if (fileName != null && Files.isRegularFile(file)) {
          fileNames.add(fileName.toString());
        }
      }
    }
    fileNames.sort(
        new Comparator<String>() {
          @Override
          public int compare(String x, String y) {
            int folded = String.CASE_INSENSITIVE_ORDER.compare(x, y);
            return folded != 0 ? folded : x.compareTo(y);
          }
        });
    List<Entry> entries = new ArrayList<Entry>(fileNames.size());
    List<String> names = new ArrayList<String>();
    for (String fileName : fileNames) {
      Path file = directory.resolve(fileName);
      String name = fileName.substring(0, fileName.length() - SUFFIX.length());
      String problem = MacroNames.check(name, names) != null ? "not a usable macro name" : null;
      Macro macro = null;
      if (problem == null) {
        try {
          macro = MacroFormat.parse(read(file), name);
        } catch (MacroFormat.Refusal | IOException e) {
          problem = e.getMessage();
        }
      }
      if (problem != null && warned.add(fileName)) {
        LOG.warn(
            "Macro file {} unreadable: {}", LogText.oneLine(fileName), LogText.oneLine(problem));
      }
      names.add(name);
      entries.add(new Entry(name, macro, problem));
    }
    return entries;
  }

  @WorkerThread
  private static String read(Path file) throws IOException {
    byte[] bytes;
    try (InputStream in = Files.newInputStream(file)) {
      bytes = in.readNBytes(MAX_BYTES + 1);
    }
    if (bytes.length > MAX_BYTES) {
      throw new IOException("over " + MAX_BYTES + " bytes");
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("not UTF-8 text", e);
    }
  }

  /**
   * Writes a named macro's file.
   *
   * @param macro the macro
   * @throws IOException when the folder or the file cannot be written
   */
  @WorkerThread
  void write(Macro macro) throws IOException {
    if (macro.name() == null || MacroNames.check(macro.name()) != null) {
      throw new IOException("not a usable macro name: " + macro.name());
    }
    byte[] content;
    try {
      content = MacroFormat.write(macro).getBytes(StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IOException("macro not writable: " + e.getMessage(), e);
    }
    Files.createDirectories(directory);
    AtomicFiles.write(SafePaths.child(directory, macro.name() + SUFFIX), content);
    warned.remove(macro.name() + SUFFIX);
  }

  @WorkerThread
  void remove(String name) throws IOException {
    Files.deleteIfExists(SafePaths.child(directory, name + SUFFIX));
  }

  @WorkerThread
  void move(String from, String to) throws IOException {
    if (MacroNames.check(to) != null) {
      throw new IOException("not a usable macro name: " + to);
    }
    Path source = SafePaths.child(directory, from + SUFFIX);
    if (!Files.exists(source)) {
      throw new NoSuchFileException(source.toString());
    }
    Files.move(source, SafePaths.child(directory, to + SUFFIX));
  }
}
