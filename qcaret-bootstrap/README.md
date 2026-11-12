# qcaret-bootstrap

The entry point of the universal `qcaret.jar`. It depends on the JDK only and
loads every other QCaret class, and SWT, through its own class loader.

## Startup

`Main` is compiled for Java 8 so an older runtime prints
`QCaret requires Java 21 or later; <java.home> is Java <version>.` and exits 2
instead of failing with `UnsupportedClassVersionError`. It reaches `Boot`
through reflection, because a Java 8 compilation cannot link to Java 21
bytecode. `Boot.start` then:

1. Maps `os.name` and `os.arch` to a `Target` (`Target.current`, the only
   bootstrap reader of `os.name`); any other platform prints one line and
   exits 2.
2. On macOS without `-XstartOnFirstThread`, relaunches (`MacRelaunch`).
3. Reads `META-INF/qcaret/index.properties` and prepares the natives
   (`Natives`).
4. Sets `swt.library.path` and `qcaret.home`, builds a `NestedJarClassLoader`
   over the target's SWT fragment and the application jars, makes it the
   context class loader, and runs `io.github.ajdailey.qcaret.launcher.Main`.

`qcaret.home` is the parent of `lib/` when the jar sits in one, else the jar's
directory. Exit status 3 means the natives could not be prepared.

## Universal jar

| Entry | Contents |
|---|---|
| `io/github/ajdailey/qcaret/bootstrap/` | This module |
| `META-INF/qcaret/index.properties` | `swt.version`, `lib` (nested jar names), `natives.<target>` (library names) |
| `META-INF/qcaret/lib/*.jar` | Application jars, slf4j-api, slf4j-jdk14 |
| `META-INF/qcaret/swt/<target>.jar` | The six SWT fragments, unmodified |
| `META-INF/qcaret/natives/<target>/<library>.sha256` | SHA-256 of each native, hex |

Every name in the index must match `[A-Za-z0-9._-]+`; the bootstrap refuses
the jar otherwise.

## Nested-jar class loader

`NestedJarClassLoader` reads classes and resources from nested jars in place
through the JDK zip file system (`jdk.zipfs`); nothing is extracted to disk.
`URLClassLoader` cannot do this: a `jar:` URL cannot name an entry inside a
nested jar. The parent is the platform class loader, so no QCaret or SWT class
is visible through the application class loader.

- Resources resolve to `qcaret-nested:` URLs. SWT calls `getResource` on its own
  classes while loading natives and throws `NullPointerException` without one.
- `findResources` returns every root's match, which `ServiceLoader` needs for
  the SLF4J provider.
- The loader is the main thread's context class loader. `LogManager` loads the
  launcher's `RetainingLogManager` through it after the application class
  loader fails.
- Each package is defined from its jar's manifest, so
  `Package.getImplementationVersion()` works for `-v`.
- The zip file systems stay open for the life of the process.

## Natives

The OS loads a native library only from a file. The bootstrap supplies
`swt.library.path` with every library in place, so SWT never extracts one
itself (its own extraction runs `chmod` through `Runtime.exec`).

| Case | Directory | Action |
|---|---|---|
| Linux or Windows bundle: `<qcaret.home>/lib/swt/` exists | `<qcaret.home>/lib/swt/` | Verify; never write |
| `--settings <dir>` | `<dir>/cache/swt/<swt version>-<target>/` | Verify; extract when missing or mismatched |
| Portable: `<qcaret.home>/qcaret.conf` exists | `<qcaret.home>/cache/swt/<swt version>-<target>/` | Verify; extract |
| Linux | `${XDG_CACHE_HOME:-~/.cache}/qcaret/swt/<swt version>-<target>/` | Verify; extract |
| Windows | `%LOCALAPPDATA%\QCaret\cache\swt\<swt version>-<target>\` | Verify; extract |
| macOS | `~/Library/Caches/io.github.ajdailey.qcaret/swt/<swt version>-<target>/` | Verify; extract |

Every start checks the SHA-256 of every library against the jar's digest.

A `--settings` directory the launch creates is owner-only (POSIX `rwx------`); an
existing one keeps its permissions.

Extraction creates the directory owner-only (POSIX `rwx------`), writes to a
temp file in it, checks the digest, then moves it into place with
`ATOMIC_MOVE`. When the move fails and the name already holds a matching
library, extraction succeeds: on Windows, two first starts both extract, and
the second's replace fails while the first has the library loaded. A library
that still mismatches after one extraction, or any mismatch in a bundle, prints
`SWT native <name> in <dir> does not match this QCaret build.` and exits 3.

## macOS relaunch

SWT on macOS must create its Display on the process main thread, which needs
`-XstartOnFirstThread`. The JDK launcher sets
`JAVA_STARTED_ON_FIRST_THREAD_<pid>=1` with the flag. Without it, the
bootstrap runs this JVM's own binary again with
`-XstartOnFirstThread -Dqcaret.relaunched=true -jar <qcaret.jar>` and the original
arguments as separate list elements, without a shell, and exits with the
child's status. A relaunched process that is still off the main thread prints
`QCaret could not start on the macOS main thread.` and exits 2. JVM options of
the first process are not carried over.

## Testing

| Class | Covers |
|---|---|
| `MainTest` | Java 21 and later accepted, the message naming the running Java, `Main` compiled as Java 8 bytecode; `java8PrintsMessageAndExits2` runs `Main` under the Java 8 binary `-Dqcaret.test.java8=<path to java>` names and skips without it |
| `TargetTest` | Supported `os.name` and `os.arch` pairs, other pairs refused with both properties named, `current` matching this JVM |
| `BootTest` | `--settings` found before `--`, `qcaret.home` as the parent of `lib/`, a created settings directory owner-only and an existing one keeping its permissions, the natives location precedence, a relative `XDG_CACHE_HOME` ignored, the macOS relaunch decision and its command keeping each argument |
| `NativesTest` | Extraction into an owner-only directory, a tampered library extracted again, a failed move over a matching library succeeding and over a mismatched one failing, a bundle directory verified and never written, names outside the directory refused |
| `NestedJarClassLoaderTest` | Classes and resources loaded from nested jars |
| `BootstrapArchitectureTest` | The module on the JDK only, process creation only in `MacRelaunch`, `System.exit` only in `Main`, the string `os.name` only in `Target`; each rule shown to fail on a fixture class |
