package io.github.ajdailey.qcaret.app.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ajdailey.qcaret.app.view.DecodedImage;
import io.github.ajdailey.qcaret.app.view.ManualUiDispatcher;
import io.github.ajdailey.qcaret.app.view.MarkdownImageDecoder;
import io.github.ajdailey.qcaret.app.view.MarkdownImageRequest;
import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.markdown.MarkdownRenderer;
import io.github.ajdailey.qcaret.core.markdown.RenderedMarkdown;
import io.github.ajdailey.qcaret.core.testkit.Poll;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MarkdownImages}: sizes from the image attributes, decoding on a worker, placeholders, the
 * cache across renders and the drop of a canceled decode's results.
 */
class MarkdownImagesTest {
  private static final Duration BOUND = Duration.ofSeconds(20);

  @TempDir Path temp;

  private final ManualUiDispatcher ui = new ManualUiDispatcher();
  private final List<Throwable> failures = new CopyOnWriteArrayList<Throwable>();
  private final Workers workers =
      new Workers(
          2,
          new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
              failures.add(e);
            }
          });

  @AfterEach
  void close() {
    assertTrue(workers.close(Duration.ofSeconds(5)));
    assertEquals(List.of(), failures);
  }

  /** Counts decodes and the threads they ran on. */
  private static final class Counting extends FakeMarkdownPane {
    final AtomicInteger decodes = new AtomicInteger();
    final List<String> threads = new CopyOnWriteArrayList<String>();

    Counting() {
      super(null);
    }

    @Override
    public MarkdownImageDecoder imageDecoder() {
      final MarkdownImageDecoder inner = super.imageDecoder();
      return new MarkdownImageDecoder() {
        @Override
        public int maxWidth() {
          return inner.maxWidth();
        }

        @Override
        public DecodedImage decode(
            byte[] bytes, MarkdownImageRequest request, BooleanSupplier canceled) {
          decodes.incrementAndGet();
          threads.add(Thread.currentThread().getName());
          return inner.decode(bytes, request, canceled);
        }
      };
    }
  }

  private RenderedMarkdown render(String text) {
    return new MarkdownRenderer().render(text, temp, new CancellationToken());
  }

  private void awaitImages(final Counting pane, final int count) throws InterruptedException {
    assertTrue(
        ui.runUntil(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return pane.images.size() >= count;
              }
            },
            BOUND));
  }

  @Test
  void attributesGiveTheSizeInPixelsOrAsAShareOfThePane() {
    List<MarkdownImageRequest> requests =
        MarkdownImages.requests(
            render(
                "![a](a.png){width=120 height=80px} ![b](b.png){width=50%}"
                    + " ![c](c.png){height=10em} ![d](https://example.invalid/d.png)"));
    assertEquals(3, requests.size());
    assertEquals(120, requests.get(0).width());
    assertEquals(80, requests.get(0).height());
    assertEquals(0, requests.get(1).width());
    assertEquals(50, requests.get(1).percent());
    assertEquals(0, requests.get(2).height());
    assertEquals(temp.resolve("c.png"), requests.get(2).path());
  }

  @Test
  void decodesOnAWorkerKeepsTheCacheAndShowsPlaceholders() throws Exception {
    Files.write(temp.resolve("a.png"), new byte[] {1, 2, 3});
    Files.write(temp.resolve("empty.png"), new byte[0]);
    Files.write(temp.resolve("b.svg"), new byte[] {1});
    MarkdownImages images = new MarkdownImages(workers, ui);
    List<MarkdownImageRequest> requests =
        MarkdownImages.requests(
            render("![a](a.png) ![e](empty.png) ![s](b.svg) ![m](missing.png)"));
    Counting pane = new Counting();
    assertNotNull(images.show(requests, pane));
    awaitImages(pane, 4);
    assertEquals(3, pane.decoded.get(0).height());
    assertNull(pane.decoded.get(1));
    assertNull(pane.decoded.get(2));
    assertNull(pane.decoded.get(3));
    assertEquals(2, pane.decodes.get());
    assertTrue(pane.threads.get(0).startsWith("qcaret-io-"), pane.threads.get(0));
    assertEquals(3, images.cachedBytes());
    Counting again = new Counting();
    images.show(MarkdownImages.requests(render("![a](a.png)")), again);
    awaitImages(again, 1);
    assertEquals(0, again.decodes.get());
    assertSame(pane.decoded.get(0), again.decoded.get(0));
  }

  @Test
  void aCanceledDecodeShowsNothing() throws Exception {
    Files.write(temp.resolve("a.png"), new byte[] {1});
    MarkdownImages images = new MarkdownImages(workers, ui);
    Counting pane = new Counting();
    CancellationToken token =
        images.show(MarkdownImages.requests(render("![a](a.png) ![a](a.png)")), pane);
    token.cancel();
    assertTrue(
        Poll.until(
            new BooleanSupplier() {
              @Override
              public boolean getAsBoolean() {
                return workers.liveThreads() == 0;
              }
            },
            BOUND));
    ui.runQueued();
    assertEquals(List.of(), pane.images);
  }
}
