package io.github.ajdailey.qcaret.core.concurrent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that runs only on a {@link Workers} thread, never on the UI thread. A controller
 * method that blocks on files or settings carries it; the architecture test lets blocking calls in
 * controllers originate only from such a method or from a worker task's {@code run}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface WorkerThread {}
