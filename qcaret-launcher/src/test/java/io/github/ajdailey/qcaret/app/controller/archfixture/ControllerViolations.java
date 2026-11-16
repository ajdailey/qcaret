package io.github.ajdailey.qcaret.app.controller.archfixture;

import io.github.ajdailey.qcaret.app.about.UserGuide;
import io.github.ajdailey.qcaret.core.concurrent.WorkerThread;
import io.github.ajdailey.qcaret.core.concurrent.Workers;
import io.github.ajdailey.qcaret.core.util.os.OsTraits;
import io.github.ajdailey.qcaret.ui.swt.editor.SwtKeys;
import java.beans.XMLDecoder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.Timer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.swt.graphics.Point;

/** Controller classes that each break one architecture rule; never instantiated. */
final class ControllerViolations {
  private ControllerViolations() {}

  /** Uses an SWT class. */
  static final class UsesSwt {
    Point origin() {
      return new Point(0, 0);
    }
  }

  /** Uses the SWT view module. */
  static final class UsesView {
    int accelerator() {
      return SwtKeys.accelerator(null);
    }
  }

  /** Uses java.util.logging. */
  static final class UsesJul {
    Object level() {
      return java.util.logging.Level.FINE;
    }
  }

  /** Opens network connections. */
  static final class Connects {
    void connect(URL url) throws IOException {
      try (Socket socket = new Socket();
          InputStream in = url.openStream();
          SocketChannel channel = SocketChannel.open()) {
        HttpClient.newHttpClient();
      }
    }
  }

  /** Opens server, datagram and asynchronous sockets, fetches URL content and resolves names. */
  static final class ReachesNetwork {
    Object reach(URL url) throws IOException {
      new ServerSocket(0).close();
      new DatagramSocket().close();
      DatagramChannel.open().close();
      AsynchronousSocketChannel.open().close();
      InetAddress.getByName("mock.invalid");
      InetAddress.getAllByName("mock.invalid");
      return url.getContent();
    }
  }

  /** Starts processes through {@code Runtime.exec} overloads that take an environment. */
  static final class StartsProcesses {
    void start(File dir) throws IOException {
      Runtime.getRuntime().exec("mock", new String[0]);
      Runtime.getRuntime().exec(new String[] {"mock"}, new String[0], dir);
    }
  }

  /** Starts a process, deserializes, decodes XML beans and uses a weak random. */
  static final class RunsAndDecodes {
    int run(InputStream in) throws IOException {
      new ProcessBuilder("mock");
      Runtime.getRuntime().exec("mock");
      new ObjectInputStream(in).close();
      new XMLDecoder(in).close();
      return new Random().nextInt();
    }
  }

  /** Ends the JVM. */
  static final class Exits {
    void exit() {
      System.exit(1);
      Runtime.getRuntime().halt(1);
      Runtime.getRuntime().exit(1);
    }
  }

  /** Waits for other threads. */
  static final class Waits {
    void await(Future<String> future, CountDownLatch latch)
        throws InterruptedException, ExecutionException {
      future.get();
      Thread.sleep(1);
      latch.await();
    }
  }

  /** Reads the platform from a system property and the environment. */
  static final class ReadsPlatform {
    boolean windows() {
      return System.getProperty("os.name").startsWith("Windows")
          && System.getProperties().isEmpty()
          && System.getenv("WINDIR") != null;
    }
  }

  /** Asks the running system for its traits. */
  static final class AsksTheOs {
    boolean ignoresCase() {
      return OsTraits.current().fileNamesIgnoreCase();
    }
  }

  /**
   * Blocks on the UI thread and calls a worker method from it; {@code size}, {@code
   * workerCallsWorker} and the task in {@code submit} are allowed.
   */
  static final class BlocksOnTheUiThread {
    private Workers workers;

    String read(Path file) throws IOException {
      UserGuide.writeCopy(file);
      return Files.readString(file);
    }

    long uiCallsWorker(Path path) throws IOException {
      return size(path);
    }

    @WorkerThread
    long size(Path path) throws IOException {
      return Files.size(path);
    }

    @WorkerThread
    long workerCallsWorker(Path path) throws IOException {
      return size(path);
    }

    void submit(final Path path) {
      workers.tryStart(
          Workers.Kind.IO,
          new Runnable() {
            @Override
            public void run() {
              Files.isDirectory(path);
            }
          });
    }

    /** Creates two tasks, so neither counts as the submitted one. */
    void submitOneOfTwo(final Path path) {
      Runnable later =
          new Runnable() {
            @Override
            public void run() {
              Files.isSymbolicLink(path);
            }
          };
      workers.tryStart(
          Workers.Kind.IO,
          new Runnable() {
            @Override
            public void run() {
              Files.isReadable(path);
            }
          });
      later.run();
    }
  }

  /** Makes threads outside the thread owner. */
  abstract static class MakesThreads implements ThreadFactory {
    ExecutorService start(Runnable task) {
      new Thread(task).start();
      new Timer(true).cancel();
      return Executors.newSingleThreadExecutor();
    }
  }

  /** Runs work on the common {@code ForkJoinPool} without naming it. */
  abstract static class UsesTheCommonPool extends RecursiveAction {
    long run(int[] values) {
      java.util.concurrent.CompletableFuture.runAsync(
          new Runnable() {
            @Override
            public void run() {}
          });
      java.util.concurrent.CompletableFuture.supplyAsync(
              new Supplier<String>() {
                @Override
                public String get() {
                  return "mock";
                }
              })
          .thenApplyAsync(
              new Function<String, Integer>() {
                @Override
                public Integer apply(String text) {
                  return text.length();
                }
              });
      Arrays.parallelSort(values);
      return java.util.stream.IntStream.of(values).parallel().count();
    }
  }

  /** Parses XML outside the syntax package. */
  static final class ParsesXml {
    Object parser() {
      return DocumentBuilderFactory.newInstance();
    }
  }
}
