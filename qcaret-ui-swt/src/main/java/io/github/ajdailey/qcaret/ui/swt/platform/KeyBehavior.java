package io.github.ajdailey.qcaret.ui.swt.platform;

import io.github.ajdailey.qcaret.app.command.KeyStroke;
import io.github.ajdailey.qcaret.app.command.KeyTarget;
import io.github.ajdailey.qcaret.app.command.Keymap;
import org.eclipse.swt.widgets.Control;

/**
 * Key handling differences, one implementation per platform, from {@link
 * PlatformTraits#keyBehavior}: which modifiers type a character, and which window keys a main
 * window's Display filter runs.
 *
 * <table>
 *   <caption>Implementation per platform</caption>
 *   <tr><th>Platform</th><th>Class</th><th>Alt types</th><th>Filter runs</th></tr>
 *   <tr><td>win32</td><td>{@link Win32KeyBehavior}</td><td>no</td>
 *       <td>second keys; every binding and macro key in a {@code Browser}</td></tr>
 *   <tr><td>cocoa</td><td>{@link CocoaKeyBehavior}</td><td>yes</td><td>second keys</td></tr>
 *   <tr><td>GTK 3</td><td>{@link Gtk3KeyBehavior}</td><td>no</td><td>second keys</td></tr>
 *   <tr><td>GTK 4</td><td>{@link Gtk4KeyBehavior}</td><td>no</td>
 *       <td>every binding and macro key</td></tr>
 * </table>
 */
public interface KeyBehavior {
  /** The running platform's key behavior. */
  KeyBehavior PLATFORM = PlatformTraits.PLATFORM.keyBehavior();

  /**
   * Reports whether a printable character typed with modifiers inserts it: with none, with Ctrl+Alt
   * (AltGr), and on cocoa with Alt alone, as Option types there.
   *
   * @param modifiers the event's {@code SWT.CTRL}, {@code SWT.ALT} and {@code SWT.COMMAND} bits
   * @return true when the character is typed
   */
  boolean types(int modifiers);

  /**
   * Returns what a main window's Display filter runs for a key, consuming the key: a command, or a
   * saved macro. The filter runs window bindings that are not a menu item's accelerator, such as a
   * command's second key; a saved macro's key is its menu item's accelerator. On GTK 4 it runs the
   * accelerators and the macro keys too: SWT runs them from the window's shortcut controller, after
   * the focused control, so a {@code StyledText} would insert Ctrl+Shift+M's CR. On win32 it runs
   * every binding and macro key for a key in a {@code Browser}: SWT's Edge sends keys from
   * WebView2's process, so the window's native accelerators never see them.
   *
   * @param keymap the window's keymap
   * @param stroke the key
   * @param focus the control the key went to
   * @return the target, or null
   */
  KeyTarget windowTarget(Keymap keymap, KeyStroke stroke, Control focus);
}
