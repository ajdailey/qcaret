package io.github.ajdailey.qcaret.app.controller;

import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ISO-8859-1 under another name, forced on a load. Once {@link #arm}ed, the next decoder request,
 * which a load makes after reading the file, blocks until {@link #release}; once {@link
 * #armEncoder}ed, the next encoder request does, which a save makes before it touches the target.
 * Each gate holds once.
 */
final class GatedCharset extends Charset {
  private final AtomicBoolean armed = new AtomicBoolean();
  private final AtomicBoolean encoderArmed = new AtomicBoolean();
  private final CountDownLatch reached = new CountDownLatch(1);
  private final CountDownLatch released = new CountDownLatch(1);

  GatedCharset() {
    super("X-QCARET-TEST-GATE", null);
  }

  void arm() {
    armed.set(true);
  }

  void armEncoder() {
    encoderArmed.set(true);
  }

  boolean awaitHeld() throws InterruptedException {
    return reached.await(20, TimeUnit.SECONDS);
  }

  void release() {
    released.countDown();
  }

  @Override
  public boolean contains(Charset other) {
    return StandardCharsets.ISO_8859_1.contains(other);
  }

  @Override
  public CharsetDecoder newDecoder() {
    hold(armed);
    return StandardCharsets.ISO_8859_1.newDecoder();
  }

  @Override
  public CharsetEncoder newEncoder() {
    hold(encoderArmed);
    return StandardCharsets.ISO_8859_1.newEncoder();
  }

  private void hold(AtomicBoolean gate) {
    if (gate.compareAndSet(true, false)) {
      reached.countDown();
      try {
        released.await(20, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
