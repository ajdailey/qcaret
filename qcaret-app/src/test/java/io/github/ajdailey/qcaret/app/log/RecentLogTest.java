package io.github.ajdailey.qcaret.app.log;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RecentLogTest {
  @Test
  void keepsTheNewestLines() {
    RecentLog log = new RecentLog(3, 100);
    for (int i = 1; i <= 5; i++) {
      log.append("line " + i + "\n");
    }
    assertEquals(List.of("line 3", "line 4", "line 5"), log.snapshot());
  }

  @Test
  void cutsLongLines() {
    RecentLog log = new RecentLog(2, 4);
    log.append("abcdefgh");
    assertEquals(List.of("abcd..."), log.snapshot());
  }

  @Test
  void concurrentAppendsKeepCapacity() throws Exception {
    final RecentLog log = new RecentLog(RecentLog.LINES, RecentLog.LINE_MAX_CHARS);
    final CountDownLatch done = new CountDownLatch(4);
    for (int t = 0; t < 4; t++) {
      Thread thread =
          new Thread(
              new Runnable() {
                @Override
                public void run() {
                  for (int i = 0; i < 1000; i++) {
                    log.append("x" + i);
                  }
                  done.countDown();
                }
              });
      thread.start();
    }
    assertEquals(true, done.await(30, TimeUnit.SECONDS));
    assertEquals(RecentLog.LINES, log.snapshot().size());
  }
}
