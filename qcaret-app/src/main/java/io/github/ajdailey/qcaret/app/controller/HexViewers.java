package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.ClipboardService;
import io.github.ajdailey.qcaret.app.view.HexSelection;
import io.github.ajdailey.qcaret.app.view.HexView;
import io.github.ajdailey.qcaret.app.view.MainView;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.hex.FileHexSource;
import io.github.ajdailey.qcaret.core.io.FileStamp;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Hex Viewer windows a window opened: a file's bytes on disk, any size, read through {@link
 * FileHexSource} with no document. Opening resolves the path through {@link SafePaths} and takes
 * the file's stamp on a {@code qcaret-io-} thread under the busy cursor; a failure shows the open
 * error. A read that finds the file changed, or a stamp check when the window gains the focus,
 * shows a notice whose Reload opens the file again. Sessions do not restore the windows. Confined
 * to the UI thread.
 */
final class HexViewers {
  private final Workers workers;
  private final UiDispatcher ui;
  private final MainView view;
  private final List<Viewer> open = new ArrayList<Viewer>();
  private int bytesPerRow;
  private boolean disposed;

  HexViewers(Workers workers, UiDispatcher ui, MainView view, int bytesPerRow) {
    this.workers = workers;
    this.ui = ui;
    this.view = view;
    this.bytesPerRow = bytesPerRow;
  }

  /**
   * Opens a file in a new Hex Viewer window.
   *
   * @param path the file as the user named it
   */
  void open(final Path path) {
    new BusyWork(ui, view) {
      private FileHexSource source;
      private IOException error;

      @Override
      @WorkerThread
      void work() {
        try {
          source = FileHexSource.open(SafePaths.userFile(path));
        } catch (IOException e) {
          error = e;
        }
      }

      @Override
      void finished(boolean completed) {
        if (disposed) {
          return;
        }
        if (error != null) {
          view.showInfo(UiText.OPEN_TITLE.text(), OpenFailure.text(path, error));
        } else if (source != null) {
          show(source);
        }
      }
    }.start(workers, Workers.Kind.IO);
  }

  /**
   * Asks for files and opens each in a Hex Viewer window.
   *
   * @param directory where the chooser starts, or null
   */
  void choose(Path directory) {
    view.chooseFilesToOpen(
        directory,
        new Consumer<List<Path>>() {
          @Override
          public void accept(List<Path> paths) {
            for (Path path : paths) {
              open(path);
            }
          }
        });
  }

  /** Sets every viewer's bytes per row. */
  void setBytesPerRow(int value) {
    bytesPerRow = value;
    for (Viewer viewer : open) {
      viewer.controller.setBytesPerRow(value);
    }
  }

  /** Returns the open viewers' controllers, oldest first. */
  List<HexController> controllers() {
    List<HexController> list = new ArrayList<HexController>();
    for (Viewer viewer : open) {
      list.add(viewer.controller);
    }
    return list;
  }

  /** Stops every viewer's work; results arriving later are dropped. */
  void dispose() {
    disposed = true;
    for (Viewer viewer : open) {
      viewer.controller.dispose();
    }
    open.clear();
  }

  private void show(FileHexSource source) {
    Viewer viewer = new Viewer(source);
    open.add(viewer);
    viewer.controller.setSource(source);
    String name = String.valueOf(source.file().getFileName());
    HexView window = view.openHexViewer(UiText.HEX_VIEWER_TITLE.format(name), viewer.controller);
    if (window == null) {
      viewer.viewClosed();
      return;
    }
    viewer.controller.attach(window);
  }

  /** One Hex Viewer window. */
  private final class Viewer implements HexController.Host {
    final HexController controller;
    private FileHexSource source;
    private boolean checking;
    private boolean recheck;

    Viewer(FileHexSource source) {
      this.source = source;
      this.controller = new HexController(workers, ui, this, true, bytesPerRow);
    }

    /** Opens the file again and shows its bytes. */
    void reload() {
      final Path file = source.file();
      new BusyWork(ui, view) {
        private FileHexSource next;
        private IOException error;

        @Override
        @WorkerThread
        void work() {
          try {
            next = FileHexSource.open(file);
          } catch (IOException e) {
            error = e;
          }
        }

        @Override
        void finished(boolean completed) {
          if (!open.contains(Viewer.this)) {
            return;
          }
          if (error != null) {
            view.showInfo(UiText.OPEN_TITLE.text(), OpenFailure.text(file, error));
          } else if (next != null) {
            source = next;
            controller.notice(null, null);
            controller.setSource(next);
          }
        }
      }.start(workers, Workers.Kind.IO);
    }

    /**
     * Compares the file's stamp with the source's on a worker; a change shows the notice. A check
     * asked for while one runs follows it.
     */
    void check() {
      if (checking) {
        recheck = true;
        return;
      }
      checking = true;
      final FileHexSource checked = source;
      boolean started =
          workers.tryStart(
              Workers.Kind.IO,
              new Runnable() {
                @Override
                public void run() {
                  boolean same;
                  try {
                    same = checked.stamp().equals(FileStamp.read(checked.file()));
                  } catch (IOException e) {
                    same = false;
                  }
                  final boolean changed = !same;
                  ui.asyncExec(
                      new Runnable() {
                        @Override
                        public void run() {
                          checking = false;
                          if (changed && source == checked && open.contains(Viewer.this)) {
                            sourceChanged();
                          } else if (recheck && open.contains(Viewer.this)) {
                            recheck = false;
                            check();
                          }
                        }
                      });
                }
              });
      if (!started) {
        checking = false;
      }
    }

    @Override
    public ClipboardService clipboard() {
      return view;
    }

    @Override
    public void selectionChanged(HexSelection selection) {
      // A viewer has no editor to follow.
    }

    @Override
    public void focusChanged(boolean focused) {
      if (focused) {
        check();
      }
    }

    @Override
    public void sourceChanged() {
      controller.notice(UiText.HEX_CHANGED_ON_DISK.text(), UiText.HEX_RELOAD.text());
    }

    @Override
    public void noticeAction() {
      reload();
    }

    @Override
    public void viewClosed() {
      controller.dispose();
      open.remove(this);
    }
  }
}
