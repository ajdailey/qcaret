package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.buffer.TextSnapshot;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.document.SelectionSet;
import io.github.ajdailey.qcaret.core.edit.CodecEdits;
import io.github.ajdailey.qcaret.core.edit.CodecException;
import io.github.ajdailey.qcaret.core.edit.Edit;
import io.github.ajdailey.qcaret.core.edit.TextCodec;
import java.util.concurrent.CancellationException;

/**
 * One view's Edit &gt; Encode/Decode runs ({@link CodecEdits}): each converts the non-empty
 * selections on a {@code qcaret-cpu-} worker and applies the result on the UI thread as one undo
 * step, with the editor's busy cursor from the command to the apply. No range, a refused selection
 * or a result for an older revision leaves the text as it is with a notice. A newer run or Esc
 * cancels the running one. Confined to the UI thread.
 */
final class CodecRuns {
  private final EditorController editor;
  private final Document document;
  private final SelectionModel selections;
  private final Workers workers;
  private final UiDispatcher ui;
  private CancellationToken running;

  CodecRuns(EditorController editor, Workers workers, UiDispatcher ui) {
    this.editor = editor;
    this.document = editor.document();
    this.selections = editor.selections();
    this.workers = workers;
    this.ui = ui;
  }

  /** Cancels the running conversion. */
  void cancel() {
    if (running != null) {
      running.cancel();
    }
  }

  /**
   * Starts an Encode/Decode command.
   *
   * @param command one of the Encode/Decode commands
   */
  void convert(CommandId command) {
    if (document.isReadOnly()) {
      return;
    }
    final SelectionSet set = selections.selections();
    if (!CodecEdits.hasRange(set)) {
      editor.macros().failed(UiText.CODEC_NO_SELECTION.text());
      return;
    }
    final TextCodec codec = codec(command);
    final TextSnapshot text = document.text();
    final long revision = document.revision();
    cancel();
    final CancellationToken token = new CancellationToken();
    running = token;
    boolean started =
        new BusyWork(ui, editor.busyCursor()) {
          private Edit edit;
          private CodecException refused;

          @Override
          @WorkerThread
          void work() {
            try {
              edit = CodecEdits.convert(text, set, codec, token);
            } catch (CodecException e) {
              refused = e;
            } catch (CancellationException e) {
              // Esc, a newer conversion, or the view closed.
            }
          }

          @Override
          void finished(boolean completed) {
            if (token != running) {
              return;
            }
            running = null;
            apply(revision, set, edit, refused, token.isCanceled());
          }
        }.startCpu(workers);
    if (!started) {
      running = null;
    }
  }

  private void apply(
      long revision, SelectionSet set, Edit edit, CodecException refused, boolean canceled) {
    if (editor.disposed() || document.isReadOnly() || canceled) {
      return;
    }
    if (document.revision() != revision) {
      if (edit != null || refused != null) {
        editor.macros().failed(UiText.CODEC_STALE.text());
      }
      return;
    }
    if (refused != null) {
      editor.macros().failed(refusal(refused));
      return;
    }
    if (edit == null) {
      return;
    }
    document.clearPreedit();
    boolean moved = !set.equals(selections.selections());
    editor.applyEdit(moved ? new Edit(edit.batch(), null) : edit);
  }

  /** Returns the notice naming a refused selection. */
  static String refusal(CodecException e) {
    UiText text =
        switch (e.reason()) {
          case NOT_BASE64 -> UiText.CODEC_NOT_BASE64;
          case NOT_UTF8 -> UiText.CODEC_NOT_UTF8;
          case UNPAIRED_SURROGATE -> UiText.CODEC_UNPAIRED_SURROGATE;
          case MALFORMED_ESCAPE -> UiText.CODEC_MALFORMED_ESCAPE;
          case LONE_SURROGATE_ESCAPE -> UiText.CODEC_LONE_SURROGATE_ESCAPE;
        };
    return text.format(e.selection());
  }

  /** Returns the conversion of an Encode/Decode command. */
  static TextCodec codec(CommandId command) {
    return switch (command) {
      case BASE64_ENCODE -> TextCodec.BASE64_ENCODE;
      case BASE64_DECODE -> TextCodec.BASE64_DECODE;
      case URL_ENCODE -> TextCodec.URL_ENCODE;
      case URL_DECODE -> TextCodec.URL_DECODE;
      case XML_ESCAPE -> TextCodec.XML_ESCAPE;
      case XML_UNESCAPE -> TextCodec.XML_UNESCAPE;
      case JSON_ESCAPE -> TextCodec.JSON_ESCAPE;
      case JSON_UNESCAPE -> TextCodec.JSON_UNESCAPE;
      default -> throw new IllegalArgumentException(command.name());
    };
  }
}
