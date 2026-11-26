package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;

/**
 * Foreground rights a launch passes to the running instance it hands its files to, one
 * implementation per system from {@link OsTraits#foregroundRights}. Windows lets only the
 * foreground process, or a process it permits, bring a window to the front; a launch started from
 * Explorer holds that right and the running instance does not, so the instance's raise only flashes
 * its taskbar button. Call before the handoff.
 *
 * <table>
 *   <caption>Implementation per system</caption>
 *   <tr><th>System</th><th>Class</th><th>Grant</th></tr>
 *   <tr><td>Windows</td><td>{@link WindowsForegroundRights}</td>
 *       <td>{@code AllowSetForegroundWindow(ASFW_ANY)}</td></tr>
 *   <tr><td>macOS, Linux</td><td>{@link NoForegroundRights}</td><td>none</td></tr>
 * </table>
 */
public interface ForegroundRights {
  /**
   * Lets any process bring a window to the front, as this process may.
   *
   * @throws IOException when the call fails or cannot be linked
   */
  void grantAny() throws IOException;
}
