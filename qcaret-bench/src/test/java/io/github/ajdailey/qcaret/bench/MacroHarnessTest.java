package io.github.ajdailey.qcaret.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.controller.MacroBench;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Each playback workload on a small document: a counted run, and a run to the end of the text. */
class MacroHarnessTest {
  @TempDir Path temp;

  @Test
  void everyWorkloadRunsItsCountAndToTheEnd() throws Exception {
    for (MacroBench.Workload workload : MacroBench.Workload.values()) {
      MacroHarness.Result counted = MacroHarness.run(temp, workload, 8, 5, false);
      assertEquals(5, counted.iterations(), workload + ": " + counted.notice());
      assertTrue(counted.summary().turns() >= 5, workload + " turns");
      MacroHarness.Result toEnd = MacroHarness.run(temp, workload, 8, 0, false);
      assertTrue(toEnd.iterations() > 5, workload + ": " + toEnd.notice());
      assertTrue(toEnd.notice().startsWith("Macro ran"), workload + ": " + toEnd.notice());
    }
  }
}
