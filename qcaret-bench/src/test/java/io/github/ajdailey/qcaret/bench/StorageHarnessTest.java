package io.github.ajdailey.qcaret.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Which JVM options keep the harness from relaunching at the launchers' heap. */
class StorageHarnessTest {
  @Test
  void aHeapOptionIsFound() {
    assertEquals("-Xmx1g", StorageHarness.heapOption(List.of("-Dx=y", "-Xmx1g")));
    assertEquals(
        StorageHarness.LAUNCHER_HEAP,
        StorageHarness.heapOption(List.of(StorageHarness.LAUNCHER_HEAP)));
    assertEquals("-XX:MaxRAM=2g", StorageHarness.heapOption(List.of("-XX:MaxRAM=2g")));
    assertEquals("-XX:MaxHeapSize=2g", StorageHarness.heapOption(List.of("-XX:MaxHeapSize=2g")));
  }

  @Test
  void otherOptionsAreNotHeapOptions() {
    assertNull(StorageHarness.heapOption(List.of("-Xms1g", "-XX:NativeMemoryTracking=summary")));
  }
}
