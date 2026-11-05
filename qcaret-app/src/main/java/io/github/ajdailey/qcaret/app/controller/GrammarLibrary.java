package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.syntax.Grammar;
import io.github.ajdailey.qcaret.core.syntax.GrammarReader;
import io.github.ajdailey.qcaret.core.syntax.GrammarSet;
import io.github.ajdailey.qcaret.core.syntax.InvalidGrammarException;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.util.Streams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The grammars in use: the built-in ones and the user's {@code *.xml} files in the grammars folder
 * of the settings directory, read and validated on a {@code qcaret-io-} worker at startup and on
 * each {@link #load}. A user file that is not a valid grammar, or over {@value #MAX_FILE_BYTES}
 * bytes, is skipped with a log warning and a notice; a user grammar with a built-in's id replaces
 * it. Until the first load ends, {@link #set} is null. Confined to the UI thread.
 */
public final class GrammarLibrary {
  /** Largest grammar file read. */
  static final int MAX_FILE_BYTES = 1 << 20;

  private static final Logger LOG = LoggerFactory.getLogger(GrammarLibrary.class);

  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final Path directory;
  private final List<Consumer<GrammarSet>> listeners = new ArrayList<Consumer<GrammarSet>>();
  private volatile List<Grammar> builtIns;
  private GrammarSet set;
  private long generation;

  /**
   * Creates the library; {@link #load} reads the grammars.
   *
   * @param workers runs the reads
   * @param ui receives the result
   * @param view shows the notice for skipped files
   * @param directory the user grammars folder, which need not exist
   */
  public GrammarLibrary(Workers workers, UiDispatcher ui, MainView view, Path directory) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.directory = directory;
  }

  /**
   * Returns the grammars in use.
   *
   * @return the set, or null before the first load ends
   */
  public GrammarSet set() {
    return set;
  }

  /**
   * Adds a listener for each new set.
   *
   * @param listener called on the UI thread
   */
  public void addListener(Consumer<GrammarSet> listener) {
    listeners.add(listener);
  }

  /** Reads the grammars again on a worker; the listeners receive the new set. */
  public void load() {
    final long started = ++generation;
    boolean running =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                read(started);
              }
            });
    if (!running) {
      LOG.debug("Grammars not read: workers are closed");
    }
  }

  @WorkerThread
  private void read(final long started) {
    List<Grammar> builtIn = builtIns;
    GrammarReader reader = new GrammarReader();
    if (builtIn == null) {
      builtIn = GrammarSet.readBuiltIns(reader);
      builtIns = builtIn;
    }
    final List<String> errors = new ArrayList<String>();
    Map<String, Grammar> users = readUsers(reader, errors);
    final GrammarSet loaded = GrammarSet.of(builtIn, users, errors);
    ui.asyncExec(
        new Runnable() {
          @Override
          public void run() {
            loaded(started, loaded, errors);
          }
        });
  }

  /**
   * Reads every {@code *.xml} file of the folder, by file name; a failure goes to {@code errors}.
   */
  @WorkerThread
  private Map<String, Grammar> readUsers(GrammarReader reader, List<String> errors) {
    Map<String, Grammar> users = new LinkedHashMap<String, Grammar>();
    if (!Files.isDirectory(directory)) {
      return users;
    }
    List<Path> files = new ArrayList<Path>();
    try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory, "*.xml")) {
      for (Path file : listing) {
        files.add(file);
      }
    } catch (IOException e) {
      errors.add(directory.getFileName() + ": " + e.getMessage());
      return users;
    }
    files.sort(null);
    for (Path file : files) {
      String name = String.valueOf(file.getFileName());
      try (InputStream in = Files.newInputStream(file)) {
        byte[] bytes = Streams.readLimited(in, MAX_FILE_BYTES);
        users.put(name, reader.read(new ByteArrayInputStream(bytes), name));
      } catch (IOException | InvalidGrammarException e) {
        errors.add(e instanceof InvalidGrammarException ? e.getMessage() : name + ": " + e);
      }
    }
    return users;
  }

  private void loaded(long started, GrammarSet loaded, List<String> errors) {
    if (started != generation) {
      return;
    }
    set = loaded;
    for (String error : errors) {
      LOG.warn("Grammar skipped: {}", LogText.oneLine(error));
    }
    if (!errors.isEmpty()) {
      view.showNotice(
          UiText.NOTICE_GRAMMARS_SKIPPED.format(errors.size(), LogText.oneLine(errors.get(0))));
    }
    for (Consumer<GrammarSet> listener : new ArrayList<Consumer<GrammarSet>>(listeners)) {
      listener.accept(loaded);
    }
  }
}
