package io.github.ajdailey.qcaret.ui.swt.hex;

import io.github.ajdailey.qcaret.app.command.CommandId;
import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.Keymap;
import io.github.ajdailey.qcaret.app.i18n.UiText;
import io.github.ajdailey.qcaret.app.view.HexCopy;
import io.github.ajdailey.qcaret.app.view.HexInput;
import io.github.ajdailey.qcaret.app.view.HexKey;
import io.github.ajdailey.qcaret.app.view.UiDispatcher;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * A {@link HexCanvas}'s input, forwarded to its {@link HexInput}.
 *
 * <ul>
 *   <li>The editor bindings of the caret moves move the hex caret: a char or line move by a byte or
 *       a row, line start and end to the row's, document start and end to the source's, page moves
 *       by a page; the select variants extend. Tab switches the column.
 *   <li>The editor keys of Copy, Select All and Esc act on the view. In a Hex Viewer window the
 *       window keys of Find, Replace, Find Next and Previous, Go to Line and Close run too; in a
 *       main window its menu runs them. On cocoa a menu accelerator fires before the key reaches
 *       the canvas, so the main menus on a Hex Viewer window run the same commands through {@link
 *       #command}.
 *   <li>A press selects a byte, Shift+press extends, a drag selects from the pressed byte. A drag
 *       held above or below the rows scrolls a row every {@link #AUTOSCROLL_MILLIS}.
 *   <li>The wheel scrolls; the context menu is built on its first {@code MenuDetect}.
 * </ul>
 */
final class HexGestures {
  /** Interval between autoscroll steps of a drag held outside the rows. */
  static final int AUTOSCROLL_MILLIS = 50;

  /** The commands of the view around the canvas. */
  interface Commands {
    /** Opens the find bar. */
    void openFind();

    /**
     * Finds the find bar's pattern again, or opens the bar without one.
     *
     * @param forward true for Next
     */
    void findAgain(boolean forward);

    /** Closes a Hex Viewer window; a pane does nothing. */
    void close();
  }

  private final HexCanvas view;
  private final Canvas canvas;
  private final HexInput input;
  private final UiDispatcher timers;
  private final Commands commands;
  private final boolean windowKeys;
  private final Runnable autoscroll =
      new Runnable() {
        @Override
        public void run() {
          autoscrollStep();
        }
      };
  private Keymap keymap;
  private Menu menu;
  private boolean dragging;
  private boolean autoscrolling;
  private int dragX;
  private int dragY;

  HexGestures(
      HexCanvas view,
      Canvas canvas,
      HexInput input,
      Keymap keymap,
      UiDispatcher timers,
      Commands commands,
      boolean windowKeys) {
    this.view = view;
    this.canvas = canvas;
    this.input = input;
    this.keymap = keymap;
    this.timers = timers;
    this.commands = commands;
    this.windowKeys = windowKeys;
    Listener listener =
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            handle(event);
          }
        };
    int[] types = {
      SWT.KeyDown,
      SWT.Traverse,
      SWT.MouseDown,
      SWT.MouseMove,
      SWT.MouseUp,
      SWT.MouseVerticalWheel,
      SWT.MenuDetect,
      SWT.FocusIn,
      SWT.FocusOut,
      SWT.Dispose
    };
    for (int type : types) {
      canvas.addListener(type, listener);
    }
  }

  void setKeymap(Keymap next) {
    keymap = next;
  }

  /** Returns the context menu, or null before it first showed. */
  Menu menu() {
    return menu;
  }

  private void handle(Event event) {
    switch (event.type) {
      case SWT.KeyDown -> key(event);
      case SWT.Traverse -> {
        if (event.detail == SWT.TRAVERSE_TAB_NEXT || event.detail == SWT.TRAVERSE_TAB_PREVIOUS) {
          // Tab switches the column; doit false delivers the key.
          event.doit = false;
        }
      }
      case SWT.MouseDown -> pressed(event);
      case SWT.MouseMove -> moved(event);
      case SWT.MouseUp -> stopDrag();
      case SWT.MouseVerticalWheel -> {
        event.doit = false;
        input.scrollBy(-event.count);
      }
      case SWT.MenuDetect -> showMenu(event);
      case SWT.FocusIn -> {
        input.focusChanged(true);
        view.focusChanged(true);
      }
      case SWT.FocusOut -> {
        stopDrag();
        input.focusChanged(false);
        view.focusChanged(false);
      }
      case SWT.Dispose -> {
        timers.cancelTimer(autoscroll);
        if (menu != null) {
          menu.dispose();
        }
      }
      default -> {
        // Not listened for.
      }
    }
  }

  private void key(Event event) {
    if (event.keyCode == SWT.TAB && (event.stateMask & ~SWT.SHIFT) == 0) {
      event.doit = false;
      input.switchColumn();
      return;
    }
    KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
    if (stroke == null) {
      return;
    }
    CommandId editor = keymap.editorCommand(stroke);
    if (editor != null && move(editor)) {
      event.doit = false;
      return;
    }
    if (editorKey(editor) || (windowKeys && windowKey(keymap.windowBinding(stroke)))) {
      event.doit = false;
    }
  }

  /** Runs a caret move command on the hex caret; returns false for any other command. */
  private boolean move(CommandId command) {
    switch (command) {
      case CARET_LEFT, SELECT_LEFT -> input.key(HexKey.LEFT, command == CommandId.SELECT_LEFT);
      case CARET_RIGHT, SELECT_RIGHT -> input.key(HexKey.RIGHT, command == CommandId.SELECT_RIGHT);
      case CARET_UP, SELECT_UP -> input.key(HexKey.UP, command == CommandId.SELECT_UP);
      case CARET_DOWN, SELECT_DOWN -> input.key(HexKey.DOWN, command == CommandId.SELECT_DOWN);
      case PAGE_UP, SELECT_PAGE_UP ->
          input.key(HexKey.PAGE_UP, command == CommandId.SELECT_PAGE_UP);
      case PAGE_DOWN, SELECT_PAGE_DOWN ->
          input.key(HexKey.PAGE_DOWN, command == CommandId.SELECT_PAGE_DOWN);
      case LINE_START, SELECT_LINE_START ->
          input.key(HexKey.ROW_START, command == CommandId.SELECT_LINE_START);
      case LINE_END, SELECT_LINE_END ->
          input.key(HexKey.ROW_END, command == CommandId.SELECT_LINE_END);
      case DOCUMENT_START, SELECT_DOCUMENT_START ->
          input.key(HexKey.SOURCE_START, command == CommandId.SELECT_DOCUMENT_START);
      case DOCUMENT_END, SELECT_DOCUMENT_END ->
          input.key(HexKey.SOURCE_END, command == CommandId.SELECT_DOCUMENT_END);
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * Runs a command as its key would: Copy and Select All, and in a Hex Viewer window the window
   * keys.
   *
   * @param command the command
   * @return false when the view has no such command
   */
  boolean command(CommandId command) {
    return command != CommandId.CANCEL
        && (editorKey(command) || (windowKeys && windowKey(command)));
  }

  /**
   * Runs the window key of Find Next and Previous, Go to Line or Close pressed in the find field of
   * a Hex Viewer window. Find and Replace stay with the field.
   *
   * @param event the field's {@code KeyDown}
   * @return true when a command ran
   */
  boolean findFieldKey(Event event) {
    if (!windowKeys) {
      return false;
    }
    KeyStroke stroke = SwtKeys.stroke(event.stateMask, event.keyCode, event.character);
    CommandId command = stroke == null ? null : keymap.windowBinding(stroke);
    return command != CommandId.FIND && command != CommandId.REPLACE && windowKey(command);
  }

  private boolean editorKey(CommandId command) {
    if (command == null) {
      return false;
    }
    switch (command) {
      case COPY -> input.copy(HexCopy.FOCUSED);
      case SELECT_ALL -> input.selectAll();
      case CANCEL -> input.cancel();
      default -> {
        return false;
      }
    }
    return true;
  }

  private boolean windowKey(CommandId command) {
    if (command == null) {
      return false;
    }
    switch (command) {
      case FIND, REPLACE -> commands.openFind();
      case FIND_NEXT -> commands.findAgain(true);
      case FIND_PREVIOUS -> commands.findAgain(false);
      case GO_TO_LINE -> input.goToOffset();
      case CLOSE -> commands.close();
      default -> {
        return false;
      }
    }
    return true;
  }

  private void pressed(Event event) {
    if (event.button != 1) {
      return;
    }
    canvas.setFocus();
    HexGrid grid = view.grid();
    long offset = grid.offsetAt(event.x, event.y, view.topRow());
    input.clicked(offset, grid.inTextColumn(event.x), (event.stateMask & SWT.SHIFT) != 0);
    dragging = (event.stateMask & SWT.SHIFT) == 0;
  }

  private void moved(Event event) {
    if (!dragging || (event.stateMask & SWT.BUTTON1) == 0) {
      return;
    }
    dragX = event.x;
    dragY = event.y;
    drag();
    boolean outside = dragY < view.grid().lineHeight() || dragY >= canvas.getClientArea().height;
    if (outside && !autoscrolling) {
      autoscrolling = true;
      timers.timerExec(AUTOSCROLL_MILLIS, autoscroll);
    }
  }

  private void drag() {
    long offset = view.grid().offsetAt(dragX, dragY, view.topRow());
    long size = view.grid().rows().size();
    input.dragged(Math.max(0, Math.min(offset, size - 1)));
  }

  private void autoscrollStep() {
    autoscrolling = false;
    if (!dragging || canvas.isDisposed()) {
      return;
    }
    if (dragY < view.grid().lineHeight()) {
      input.scrollBy(-1);
    } else if (dragY >= canvas.getClientArea().height) {
      input.scrollBy(1);
    } else {
      return;
    }
    drag();
    autoscrolling = true;
    timers.timerExec(AUTOSCROLL_MILLIS, autoscroll);
  }

  private void stopDrag() {
    dragging = false;
    if (autoscrolling) {
      autoscrolling = false;
      timers.cancelTimer(autoscroll);
    }
  }

  private void showMenu(Event event) {
    if (menu == null) {
      menu = new Menu(canvas);
      item(UiText.COMMAND_COPY, copy(HexCopy.FOCUSED));
      item(UiText.HEX_MENU_COPY_HEX, copy(HexCopy.HEX));
      item(UiText.HEX_MENU_COPY_TEXT, copy(HexCopy.TEXT));
      item(UiText.HEX_MENU_COPY_OFFSET, copy(HexCopy.OFFSET));
      new MenuItem(menu, SWT.SEPARATOR);
      item(
          UiText.COMMAND_SELECT_ALL,
          new Runnable() {
            @Override
            public void run() {
              input.selectAll();
            }
          });
      new MenuItem(menu, SWT.SEPARATOR);
      item(
          UiText.HEX_MENU_GO_TO_OFFSET,
          new Runnable() {
            @Override
            public void run() {
              input.goToOffset();
            }
          });
      item(
          UiText.HEX_MENU_FIND,
          new Runnable() {
            @Override
            public void run() {
              commands.openFind();
            }
          });
      canvas.setMenu(menu);
    }
    boolean any = view.selection().length() > 0;
    MenuItem[] items = menu.getItems();
    for (int i = 0; i < 3; i++) {
      items[i].setEnabled(any);
    }
  }

  private Runnable copy(final HexCopy kind) {
    return new Runnable() {
      @Override
      public void run() {
        input.copy(kind);
      }
    };
  }

  private void item(UiText label, final Runnable action) {
    MenuItem item = new MenuItem(menu, SWT.PUSH);
    item.setText(label.text());
    item.addListener(
        SWT.Selection,
        new Listener() {
          @Override
          public void handleEvent(Event event) {
            action.run();
          }
        });
  }
}
