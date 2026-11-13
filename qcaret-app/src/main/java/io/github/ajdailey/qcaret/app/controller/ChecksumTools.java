package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandGroup;
import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.io.ChecksumAlgorithm;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Tools menu: for each algorithm Generate ({@link TextChecksum}), Generate from Files ({@link
 * FileChecksums}) and Generate from Selection into Clipboard ({@link SelectionChecksum}), plus
 * Verify Checksums ({@link ChecksumVerify}). An algorithm no security provider offers, as MD5 under
 * a FIPS-only provider set, is logged once per process and its commands stay disabled. Confined to
 * the UI thread.
 */
final class ChecksumTools {
  private static final Logger LOG = LoggerFactory.getLogger(ChecksumTools.class);

  private final DocumentController documents;
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final SelectionChecksum selection;

  /** Creates a digest, for the availability probe. */
  interface DigestSource {
    /**
     * Creates a digest.
     *
     * @param algorithm the algorithm
     * @return a new digest
     * @throws NoSuchAlgorithmException when no provider offers it
     */
    MessageDigest create(ChecksumAlgorithm algorithm) throws NoSuchAlgorithmException;
  }

  /** The algorithms the runtime offers, probed on first use. */
  private static final class Available {
    static final Set<ChecksumAlgorithm> SET =
        probe(
            new DigestSource() {
              @Override
              public MessageDigest create(ChecksumAlgorithm algorithm)
                  throws NoSuchAlgorithmException {
                return algorithm.newDigest();
              }
            });
  }

  ChecksumTools(DocumentController documents, Workers workers, UiDispatcher ui, MainView view) {
    this.documents = documents;
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.selection = new SelectionChecksum(documents, workers, ui, view);
  }

  /**
   * Returns the algorithms a source can create, logging each one it cannot.
   *
   * @param source creates digests
   * @return the algorithms offered
   */
  static Set<ChecksumAlgorithm> probe(DigestSource source) {
    Set<ChecksumAlgorithm> out = EnumSet.noneOf(ChecksumAlgorithm.class);
    for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
      try {
        source.create(algorithm);
        out.add(algorithm);
      } catch (NoSuchAlgorithmException e) {
        // The exception names the algorithm.
        LOG.warn("A checksum algorithm is not available; its Tools commands are disabled", e);
      }
    }
    return out;
  }

  /**
   * Reports whether the runtime offers an algorithm.
   *
   * @param algorithm the algorithm
   * @return true when a provider offers it
   */
  static boolean available(ChecksumAlgorithm algorithm) {
    return Available.SET.contains(algorithm);
  }

  /**
   * Creates a digest of an algorithm the runtime offers.
   *
   * @param algorithm an available algorithm
   * @return a new digest
   */
  static MessageDigest digest(ChecksumAlgorithm algorithm) {
    try {
      return algorithm.newDigest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(algorithm.javaName() + " is not available", e);
    }
  }

  /**
   * Returns a dialog note: for MD5 and SHA-1, that the algorithm is no security check.
   *
   * @param algorithm the algorithm
   * @param lead the text before it, or empty
   * @return the note
   */
  static String note(ChecksumAlgorithm algorithm, String lead) {
    if (algorithm.collisionResistant()) {
      return lead;
    }
    String weak = UiText.CHECKSUM_WEAK.format(algorithm.javaName());
    return lead.isEmpty() ? weak : lead + " " + weak;
  }

  /**
   * Returns the algorithm of a Tools command.
   *
   * @param command any command
   * @return the algorithm, or null for Verify Checksums and commands of other menus
   */
  static ChecksumAlgorithm algorithm(CommandId command) {
    return switch (command) {
      case MD5_TEXT, MD5_FILES, MD5_SELECTION -> ChecksumAlgorithm.MD5;
      case SHA1_TEXT, SHA1_FILES, SHA1_SELECTION -> ChecksumAlgorithm.SHA_1;
      case SHA256_TEXT, SHA256_FILES, SHA256_SELECTION -> ChecksumAlgorithm.SHA_256;
      case SHA512_TEXT, SHA512_FILES, SHA512_SELECTION -> ChecksumAlgorithm.SHA_512;
      default -> null;
    };
  }

  private static boolean selectionCommand(CommandId command) {
    return command == CommandId.MD5_SELECTION
        || command == CommandId.SHA1_SELECTION
        || command == CommandId.SHA256_SELECTION
        || command == CommandId.SHA512_SELECTION;
  }

  private static boolean filesCommand(CommandId command) {
    return command == CommandId.MD5_FILES
        || command == CommandId.SHA1_FILES
        || command == CommandId.SHA256_FILES
        || command == CommandId.SHA512_FILES;
  }

  /**
   * Reports whether a Tools command's item is enabled: its algorithm is available, and a selection
   * command needs a selection.
   *
   * @param command a command of {@link CommandGroup#TOOLS}
   * @return true when it can run
   */
  boolean enabled(CommandId command) {
    ChecksumAlgorithm algorithm = algorithm(command);
    if (algorithm == null) {
      return true;
    }
    if (!available(algorithm)) {
      return false;
    }
    if (!selectionCommand(command)) {
      return true;
    }
    EditorController editor = documents.activeEditor();
    return editor != null && editor.hasSelection();
  }

  /**
   * Returns why a Tools command's algorithm is disabled, for its cascade's tool tip.
   *
   * @param command any command
   * @return the reason, or null when the command has no algorithm or the algorithm is available
   */
  String unavailable(CommandId command) {
    ChecksumAlgorithm algorithm = algorithm(command);
    return algorithm == null || available(algorithm)
        ? null
        : UiText.CHECKSUM_UNAVAILABLE.format(algorithm.javaName());
  }

  /**
   * Runs a Tools command.
   *
   * @param command a command of {@link CommandGroup#TOOLS}
   */
  void execute(CommandId command) {
    if (command == CommandId.VERIFY_CHECKSUMS) {
      new ChecksumVerify(documents, workers, ui, view).open();
      return;
    }
    if (!enabled(command)) {
      return;
    }
    final ChecksumAlgorithm algorithm = algorithm(command);
    if (selectionCommand(command)) {
      selection.copy(algorithm);
    } else if (filesCommand(command)) {
      Path active = documents.activePath();
      view.chooseFilesToOpen(
          active == null ? null : active.getParent(),
          new Consumer<List<Path>>() {
            @Override
            public void accept(List<Path> files) {
              if (!files.isEmpty()) {
                new FileChecksums(
                        algorithm,
                        workers,
                        ui,
                        view,
                        documents.under(),
                        documents.writes(),
                        documents::checkWritten)
                    .open(files);
              }
            }
          });
    } else {
      new TextChecksum(algorithm, workers, ui, view).open();
    }
  }
}
