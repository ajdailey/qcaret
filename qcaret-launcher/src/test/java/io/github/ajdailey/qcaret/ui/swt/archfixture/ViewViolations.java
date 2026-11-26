package io.github.ajdailey.qcaret.ui.swt.archfixture;

import io.github.ajdailey.qcaret.app.controller.ApplicationController;
import io.github.ajdailey.qcaret.app.theme.ThemeMode;
import io.github.ajdailey.qcaret.core.document.Document;
import io.github.ajdailey.qcaret.core.document.EditBatch;
import io.github.ajdailey.qcaret.core.document.Selection;
import io.github.ajdailey.qcaret.core.document.SelectionModel;
import io.github.ajdailey.qcaret.core.io.SafePaths;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import io.github.ajdailey.qcaret.ui.swt.platform.PlatformTraits;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SwtCallable;
import org.eclipse.swt.custom.StyledTextContent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolItem;

/** View classes that each break one architecture rule; never instantiated. */
final class ViewViolations {
  private ViewViolations() {}

  /** Uses an SWT internal class. */
  static final class UsesSwtInternals {
    String version() {
      return org.eclipse.swt.internal.Library.getVersionString();
    }
  }

  /** Reaches an SWT internal by reflection, outside the exception classes. */
  static final class ReflectsIntoSwt {
    Object handle(Display display) throws ReflectiveOperationException {
      Class.forName("org.eclipse.swt.internal.Library");
      Field field = Display.class.getDeclaredField("mock");
      field.setAccessible(true);
      return field.get(display);
    }
  }

  /** Uses a selection, which is allowed, and a file-system helper. */
  static final class UsesBackEnd {
    Path file(int caret) throws IOException {
      return Selection.caret(caret).isEmpty() ? SafePaths.userFile("mock.txt") : null;
    }
  }

  /** Blocks in every banned way. */
  static final class Blocks {
    int charsets() {
      return Charset.availableCharsets().size();
    }

    void block(
        Future<String> future,
        Object monitor,
        Lock lock,
        Condition condition,
        Semaphore semaphore,
        BlockingQueue<String> queue,
        CountDownLatch latch,
        FileChannel channel)
        throws InterruptedException, ExecutionException, IOException {
      future.get();
      Thread.sleep(1);
      monitor.wait();
      lock.lock();
      condition.await();
      semaphore.acquire();
      queue.put("x");
      latch.await();
      channel.close();
    }
  }

  /** Posts to the Display without the dispatcher. */
  static final class PostsToDisplay {
    void post(Display display, Runnable task) {
      display.asyncExec(task);
      display.timerExec(1, task);
      display.syncExec(task);
    }
  }

  /** Implements the widget's content outside the editor view. */
  abstract static class ImplementsContent implements StyledTextContent {}

  /** Edits a document directly. */
  static final class EditsDocument {
    void edit(Document document, SelectionModel model) {
      document.apply(EditBatch.replace(0, 0, "x"), model, null);
      document.undo(model);
      document.redo(model);
    }
  }

  /** Sets icon images without {@code ToolIcons}. */
  static final class SetsIcon {
    void set(ToolItem item, Button button, Image image) {
      item.setImage(image);
      button.setImage(image);
    }
  }

  /** Reads the palette, which is allowed, and state that {@code CommandStates} maps. */
  static final class ReadsCheckState {
    boolean dark(ApplicationController controller, Document document) {
      return controller.theme().palette() != null
          && controller.theme().mode() == ThemeMode.DARK
          && controller.documents().wrap()
          && document.isReadOnly();
    }
  }

  /** Creates an owned shell without its owner's icons. */
  static final class CreatesShell {
    Shell create(Shell parent) {
      return new Shell(parent);
    }
  }

  /** Creates a native tab folder outside the platform package. */
  static final class CreatesTabFolder {
    org.eclipse.swt.widgets.TabFolder open(Shell parent) {
      return new org.eclipse.swt.widgets.TabFolder(parent, SWT.TOP);
    }
  }

  /** Shows a table's grid lines outside the platform package. */
  static final class ShowsGridLines {
    void open(org.eclipse.swt.widgets.Table table) {
      table.setLinesVisible(true);
    }
  }

  /** Sets SWT's win32 header divider color key outside Win32HeaderLines. */
  static final class SetsHeaderLineColor {
    void open(Display display) {
      display.setData("org.eclipse.swt.internal.win32.Table.headerLineColor", null);
    }
  }

  /** Opens a native chooser outside NativeDialogs. */
  static final class OpensFileDialog {
    String choose(Shell parent) {
      return new org.eclipse.swt.widgets.FileDialog(parent).open();
    }
  }

  /**
   * Reads the SWT platform, the GTK 4 environment and an SWT internal property outside {@code
   * PlatformTraits}.
   */
  static final class ReadsPlatform {
    boolean gtk4() {
      return "gtk".equals(SWT.getPlatform()) && System.getenv("SWT_GTK4") != null;
    }

    String gtkVersion() {
      return System.getProperty("org.eclipse.swt.internal.gtk.version");
    }
  }

  /** Traits with a boolean that switches behavior. */
  interface SwitchingTraits extends PlatformTraits {
    boolean drawsSomethingElse();
  }

  /** Branches on a behavior trait instead of calling a seam. */
  static final class BranchesOnATrait {
    int pick(SwitchingTraits traits) {
      return traits.drawsSomethingElse() ? 1 : 0;
    }
  }

  /** Waits on the UI thread through {@code syncCall} and posts through {@code execute}. */
  static final class WaitsOnDisplay {
    int post(Display display, Runnable task) {
      display.execute(task);
      return display.syncCall(
          new SwtCallable<Integer, RuntimeException>() {
            @Override
            public Integer call() {
              return 1;
            }
          });
    }
  }

  /** Asks the toolkit platform questions outside the platform package. */
  static final class AsksThePlatform {
    boolean ask(Display display) {
      return display.getSystemMenu() != null
          || display.getSystemTray() != null
          || display.getSystemTaskBar() != null
          || Display.isSystemDarkTheme()
          || Program.findProgram("txt") != null;
    }
  }

  /** Branches on value traits outside their reviewed callers. */
  static final class BranchesOnAValue {
    int pick(PlatformTraits traits, OsTraits os, Path file) {
      return traits.macKeys() || os.runsInsteadOfOpening(file) ? 1 : 0;
    }
  }
}
