package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextContent;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.LoadOptions;
import io.github.ajdailey.qcaret.core.document.TextModelines;
import io.github.ajdailey.qcaret.core.text.EditorConfig;
import io.github.ajdailey.qcaret.core.text.FileSettings;
import io.github.ajdailey.qcaret.core.text.LogText;
import io.github.ajdailey.qcaret.core.text.Modeline;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the settings a file declares, from the sources the settings turn on ({@code
 * editor.editorConfig}, {@code editor.modelines}). The EditorConfig read runs on a worker; each
 * {@code .editorconfig} that contributes nothing logs one WARN per read.
 */
final class FileSettingsReader {
  private static final Logger LOG = LoggerFactory.getLogger(FileSettingsReader.class);

  private volatile boolean editorConfig = true;
  private volatile boolean modelines = true;

  /**
   * Turns the sources on or off; documents read afterwards follow.
   *
   * @param readEditorConfig true to read {@code .editorconfig} files
   * @param readModelines true to read modelines
   */
  void setSources(boolean readEditorConfig, boolean readModelines) {
    editorConfig = readEditorConfig;
    modelines = readModelines;
  }

  /**
   * Finds a file's EditorConfig properties, or none when that source is off.
   *
   * @param file the file's real path
   * @return the properties
   */
  @WorkerThread
  EditorConfig.Result editorConfig(Path file) {
    if (!editorConfig) {
      return EditorConfig.Result.NONE;
    }
    EditorConfig.Result found = EditorConfig.resolve(file);
    for (String problem : found.problems()) {
      LOG.warn("Ignored an EditorConfig file: {}", LogText.oneLine(problem));
    }
    return found;
  }

  /**
   * Returns the options a load uses: the EditorConfig charset as the configured one, and the
   * modeline switch.
   *
   * @param options the options asked for
   * @param config the file's EditorConfig properties
   * @return the options
   */
  LoadOptions options(LoadOptions options, EditorConfig.Result config) {
    if (options.allowBinary()) {
      return options;
    }
    FileSettings declared = FileSettings.of(config, Modeline.NONE);
    return options.withDeclarations(declared.declaredCharset(), modelines);
  }

  /**
   * Combines the EditorConfig properties with the text's modelines. Reads a bounded part of the
   * text ({@link TextModelines#read}).
   *
   * @param config the file's EditorConfig properties
   * @param text the document's text
   * @return the settings
   */
  FileSettings settings(EditorConfig.Result config, TextContent text) {
    Modeline modeline = modelines ? TextModelines.read(text) : Modeline.NONE;
    return FileSettings.of(config, modeline);
  }

  /**
   * Reads a file's settings for a document's text.
   *
   * @param file the file's real path
   * @param text the document's text
   * @return the settings
   */
  @WorkerThread
  FileSettings read(Path file, TextContent text) {
    return settings(editorConfig(file), text);
  }

  /**
   * Reads a file's settings on a {@code qcaret-io-} worker and passes them to {@code done} on the
   * UI thread; nothing arrives when the workers refuse the task.
   *
   * @param workers starts the task
   * @param ui runs {@code done}
   * @param reader the reader
   * @param file the file's path
   * @param text the document's text
   * @param done receives the settings
   */
  static void readLater(
      Workers workers,
      final UiDispatcher ui,
      final FileSettingsReader reader,
      final Path file,
      final TextContent text,
      final Consumer<FileSettings> done) {
    boolean started =
        workers.tryStart(
            Workers.Kind.IO,
            new Runnable() {
              @Override
              public void run() {
                final FileSettings found = reader.read(file, text);
                ui.asyncExec(
                    new Runnable() {
                      @Override
                      public void run() {
                        done.accept(found);
                      }
                    });
              }
            });
    if (!started) {
      LOG.debug("Settings not read: the workers are closed");
    }
  }

  /**
   * Names where a document's settings came from, for its tab's tool tip.
   *
   * @param settings the settings
   * @return the line, or null when no source contributed
   */
  static String sources(FileSettings settings) {
    List<String> names = new ArrayList<String>();
    for (Path file : settings.editorConfigs()) {
      names.add(file.toString());
    }
    if (settings.modeline() != null) {
      names.add(UiText.SETTINGS_MODELINE.format(settings.modeline()));
    }
    return names.isEmpty() ? null : UiText.SETTINGS_FROM.format(String.join(", ", names));
  }
}
