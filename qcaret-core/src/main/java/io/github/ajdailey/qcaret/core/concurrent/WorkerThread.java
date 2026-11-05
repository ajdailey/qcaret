package io.github.ajdailey.qcaret.core.concurrent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that runs only on a {@link Workers} thread, never on the UI thread, so it may make
 * blocking file, document and settings calls. Every caller is itself a worker method or the body of
 * a task submitted to {@code Workers}; the architecture test checks both in {@code app.controller}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface WorkerThread {}
