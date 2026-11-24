package io.github.ajdailey.qcaret.core.util.os;

/** File names on Linux and macOS: every form names a file. */
final class FileNames implements DeviceNames {
  static final FileNames INSTANCE = new FileNames();

  private FileNames() {}

  @Override
  public void checkNewName(String name) {}

  @Override
  public boolean opensDevice(String path) {
    return false;
  }

  @Override
  public void checkShare(String folder, String name) {}
}
