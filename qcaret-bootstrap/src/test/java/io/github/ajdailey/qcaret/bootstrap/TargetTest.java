package io.github.ajdailey.qcaret.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TargetTest {
  @ParameterizedTest
  @CsvSource({
    "Linux, amd64, linux-x86_64",
    "Linux, aarch64, linux-aarch64",
    "Windows 11, amd64, windows-x86_64",
    "Windows Server 2022, aarch64, windows-aarch64",
    "Mac OS X, x86_64, macos-x86_64",
    "Mac OS X, aarch64, macos-aarch64"
  })
  void mapsSupportedPairs(String osName, String osArch, String id) {
    assertEquals(id, Target.of(osName, osArch).id());
  }

  @ParameterizedTest
  @CsvSource({"Linux, x86", "Linux, riscv64", "FreeBSD, amd64", "SunOS, aarch64", "Linux, ppc64le"})
  void rejectsOtherPairs(String osName, String osArch) {
    assertNull(Target.of(osName, osArch));
  }

  @ParameterizedTest
  @CsvSource({"Linux, riscv64"})
  void unsupportedMessageNamesBothProperties(String osName, String osArch) {
    assertEquals(
        "QCaret supports Linux, Windows and macOS on x86_64 and aarch64; this Java reports"
            + " os.name=Linux, os.arch=riscv64.",
        Target.unsupportedMessage(osName, osArch));
  }
}
