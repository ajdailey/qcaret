package io.github.ajdailey.qcaret.app.controller;

import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;

/** Reads the heap and runs a garbage collection for the memory indicator. */
interface MemoryProbe {
  /** This JVM's heap, through {@link Runtime} and the heap {@link MemoryPoolMXBean}s. */
  MemoryProbe SYSTEM =
      new MemoryProbe() {
        @Override
        public MemoryStatus read() {
          List<MemoryStatus.Pool> pools = new ArrayList<MemoryStatus.Pool>();
          for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
            if (bean.getType() == MemoryType.HEAP && bean.isValid()) {
              MemoryUsage collected = bean.getCollectionUsage();
              pools.add(
                  new MemoryStatus.Pool(
                      bean.getName(),
                      bean.getUsage().getUsed(),
                      collected == null ? -1 : collected.getUsed()));
            }
          }
          Runtime runtime = Runtime.getRuntime();
          return MemoryStatus.of(
              runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), pools);
        }

        @Override
        public void collect() {
          System.gc();
        }
      };

  /**
   * Reads the heap figures.
   *
   * @return the figures
   */
  @WorkerThread
  MemoryStatus read();

  /** Requests a garbage collection. */
  @WorkerThread
  void collect();
}
