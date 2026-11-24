package io.github.ajdailey.qcaret.core.util.os;

import java.io.IOException;

/**
 * File name forms the file system changes or opens as something other than a file, one
 * implementation per system from {@link OsTraits#deviceNames}: {@link WindowsDeviceNames} on
 * Windows, {@link FileNames}, which refuses nothing, on Linux and macOS.
 */
public interface DeviceNames {
  /**
   * Rejects a name for a new or renamed entry that the file system would change: on Windows, a name
   * ending in a dot or space, which Windows drops.
   *
   * @param name one file name
   * @throws IOException naming the reason
   */
  void checkNewName(String name) throws IOException;

  /**
   * Reports whether a path opens a device rather than a file: on Windows, a {@code \\.\} or {@code
   * \\?\} prefix, or a component that is a reserved device name such as {@code CON} or {@code
   * COM1}, with or without an extension.
   *
   * @param path the path text
   * @return true on Windows for a device form
   */
  boolean opensDevice(String path);

  /**
   * Rejects a checksum list entry that names a network share the list is not on: on Windows, a UNC
   * name ({@code \\server\share\...}) on another share, since opening it connects to its host with
   * the user's credentials.
   *
   * @param folder the folder holding the list, as text
   * @param name the entry's name
   * @throws IOException naming the reason
   */
  void checkShare(String folder, String name) throws IOException;
}
