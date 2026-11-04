<picture>
  <source media="(prefers-color-scheme: dark)" srcset="qcaret-dist/src/icons/qcaret.svg">
  <img src="qcaret-dist/src/icons/qcaret-light.svg" alt="QCaret logo" width="96" height="96">
</picture>

# QCaret

QCaret is a cross-platform programmer's text editor, written in Java 21 with
SWT. It runs no macros, scripts or plugins.

Version 0.1.0 is in development. The current build opens the main window with
its theme handling, menus, About and Licenses, and remembers the window's
placement; it does not open or edit files yet. Planned for 0.1.0: large-file
editing with multiple carets and column selection, syntax highlighting for 19
languages, folding, HyperSearch across files and folders with replace, a
workspace browser, sessions and crash recovery.

## Building

Build with JDK 21 and the Maven wrapper:

```sh
xvfb-run -a ./mvnw clean verify
```

`verify` runs Spotless, Checkstyle, SpotBugs with find-sec-bugs, the ArchUnit
rules and every test, and builds the universal jar and the bundle for the
build host. Tests tagged `swt` open windows. On Linux they run when `DISPLAY`
is set and are skipped otherwise; Windows and macOS always run them, and macOS
runs them with `-XstartOnFirstThread`. Surefire sets `GDK_BACKEND=x11`, so
under `xvfb-run` the windows go to Xvfb even in a Wayland session. Fix
formatting with `./mvnw spotless:apply`.

| Command | Builds |
|---|---|
| `./mvnw clean verify` | Universal zip and the host target's bundle |
| `./mvnw clean verify -Dqcaret.target=<target>` | Universal zip and that target's bundle; compiles the UI against that target's SWT fragment and skips the `swt` tests |
| `./mvnw clean verify -Pall-targets` | Universal zip and all six bundles |

Archives are written to `dist/`, which no build step deletes. They are built on
Linux x86_64 only: packaging runs `tar`, `jlink`, `jmod`, launch4j's x86-64
Linux linker and `rcodesign`. On any other host `qcaret-dist` builds and
smoke-tests `qcaret.jar` and writes no archive. The Temurin JDK archives and
`rcodesign` are downloaded once into `~/.cache/qcaret-build/`
(`-Dqcaret.cache.dir` overrides it) and checked against pinned SHA-256
digests. Two builds of one commit produce the same `qcaret.jar`: every jar
entry carries the fixed `project.build.outputTimestamp`.

### Container build

A host with Docker (or Podman with compose) and no JDK builds in the image
`docker/Dockerfile` defines: Temurin 21, git, Xvfb and GTK 3. `compose.yaml`
holds the mounts and environment and runs the same `./mvnw`.

```sh
scripts/container.sh               # clean package -Pall-targets: seven archives into dist/
scripts/container.sh check         # xvfb-run ./mvnw clean verify, SWT tests included
scripts/container.sh clean         # ./mvnw clean, and empty dist/
scripts/container.sh shell         # a shell in the build image
scripts/container.sh --rebuild     # rebuild the build image first
scripts/container.sh --out DIR     # write the archives to DIR
scripts/container.sh ./mvnw -B spotless:check
```

Any other command runs verbatim in the image at the repository root. Windows
uses `scripts\container.bat` with the same arguments; WSL uses the Linux script.
The output directory is bind-mounted over `dist/`, so `clean` empties it and
never removes it. The Maven repository, the wrapper's Maven and the JDK cache
live in the named volume `qcaret-maven-repo`. On Linux the container runs as
the calling user, so files in `dist/` and `target/` belong to that user. The
service is `linux/amd64`, because packaging runs launch4j's x86-64 linker; an
arm64 host runs it under its engine's emulation.

### Continuous integration

`.github/workflows/ci.yml` runs on every push to `main`, every pull request and
on demand, with Temurin 21.0.12+8:

| Job | Runners | Does |
|---|---|---|
| `test` | `ubuntu-24.04`, `ubuntu-24.04-arm`, `windows-2025`, `windows-11-arm`, `macos-15-intel`, `macos-15` | `./mvnw -B verify` on each target natively, SWT tests included (Linux under `xvfb-run`) |
| `test-gtk4` | `ubuntu-24.04` | The SWT modules' tests with `SWT_GTK4=1`; gating, with a test that hits a known SWT GTK 4 defect skipped there and the issue named |
| `package` | `ubuntu-24.04` | `./mvnw -B package -Pall-targets`; uploads the seven archives as `qcaret-archives` |
| `bundle` | the six `test` runners | Unpacks the target's bundle, checks the runtime's `os.arch`, and starts the bundle's launcher until the main window opens |

On Windows the `bundle` job starts `qcaret.exe`, finds the `javaw.exe` child
running `qcaret.jar`, and passes only when the child's image is the bundle's
`runtime\bin\javaw.exe` with the target's PE machine type (`ARM64` on
`windows-11-arm`). On macOS it also runs `codesign --verify --deep --strict`
on `QCaret.app`. The `package` job caches the JDK archives and `rcodesign`
under `~/.cache/qcaret-build`, apart from the Maven cache.
`.github/workflows/dependency-check.yml` scans the dependencies for known
vulnerabilities weekly, and Dependabot proposes Maven and Actions updates.
Actions are pinned by commit SHA.

## Targets

| Target | SWT fragment | Bundle |
|---|---|---|
| linux-x86_64 | `gtk.linux.x86_64` | `qcaret-<version>-linux-x86_64.tar.gz` |
| linux-aarch64 | `gtk.linux.aarch64` | `qcaret-<version>-linux-aarch64.tar.gz` |
| windows-x86_64 | `win32.win32.x86_64` | `qcaret-<version>-windows-x86_64.zip` |
| windows-aarch64 | `win32.win32.aarch64` | `qcaret-<version>-windows-aarch64.zip` |
| macos-x86_64 | `cocoa.macosx.x86_64` | `qcaret-<version>-macos-x86_64.zip` (`QCaret.app`) |
| macos-aarch64 | `cocoa.macosx.aarch64` | `qcaret-<version>-macos-aarch64.zip` (`QCaret.app`) |

`qcaret-<version>.zip` holds the universal `lib/qcaret.jar`, which runs on all
six targets under an installed Java 21, with `bin/qcaret` and `qcaret.exe`.
Each bundle adds a Java runtime for its target, so it needs no installed Java.
Linux targets need the system GTK 3 libraries. GTK 4 is an experimental opt-in
on linux-x86_64: start QCaret with `SWT_GTK4=1` (GTK 4 libraries required).
The macOS bundles are ad-hoc signed and not notarized, so macOS asks the user
to approve the first launch.

## Command line

```text
qcaret [options] [file]...
java -jar qcaret.jar [options] [file]...
```

| Option | Effect |
|---|---|
| `-h`, `--help` | Print usage and exit 0 |
| `-v`, `--version` | Print `qcaret <version>` and exit 0 |
| `-d`, `--debug <level>` | stderr log level: `1` INFO, `2` DEBUG, `3` and above TRACE, or `error`, `warn`, `info`, `debug`, `trace`; WARN when absent |
| `-l`, `--log` | Also write the log at DEBUG to `logs/qcaret-<pid>.log` in the settings directory, for this run |
| `-m`, `--multi` | Run as an independent instance; the window placement is not saved |
| `--window-width <px>`, `--window-height <px>` | Client-area size, each independently; overrides the remembered size for this run and is not saved back; default 1024 x 768 |
| `--window-x <px>`, `--window-y <px>` | Window position, as above; ignored on Wayland, where the compositor places windows |
| `--settings <dir>` | Settings directory, created owner-only; holds `qcaret.conf` and QCaret's SWT native cache |
| `--` | End of options |
| `file` | Accepted and logged; opening files is not built yet |

Options combine: `-ml`, `--opt=value`, `-d2`. An unknown option or a bad value
prints a message to stderr and exits 1; an SWT start failure, such as missing
GTK libraries, exits 3.

## Settings directory

QCaret keeps its settings in `qcaret.conf` (Java properties, `version=1`) in
the first directory that applies:

| Mode | When | Directory |
|---|---|---|
| Explicit | `--settings <dir>` | `<dir>` |
| Portable | `qcaret.conf` exists in the installation directory, the parent of `lib/` | The installation directory |
| Per-user | Otherwise | Linux `$XDG_CONFIG_HOME/qcaret` (default `~/.config/qcaret`), Windows `%APPDATA%\QCaret`, macOS `~/Library/Application Support/QCaret` |

An empty `qcaret.conf` in the installation directory selects portable mode.
The macOS bundle is sealed, so portable use there goes through `--settings`.
In explicit and portable mode on Linux, `user.home` is the settings directory,
so GTK's `.swt/trims.prefs` lands there too. A malformed or unreadable file, or
one over 1 MiB, is ignored with one warning and kept as `qcaret.conf.invalid`
before the next save.

| Key | Values | Default | Set from |
|---|---|---|---|
| `ui.theme` | `light`, `dark`, `system` | `light` | View > Theme |
| `window.x`, `.y`, `.width`, `.height`, `.maximized` | The normal position, client size and maximized state at the last exit | none | Exit, unless a `--window-*` option or `-m` was given |

A value out of range falls back to its default. Light and dark also set the
native controls' theme; Windows applies a change to native controls at the
next start, and macOS native controls follow the system appearance.

## Layout

| Path | Contents |
|---|---|
| `qcaret-bootstrap/` | Entry point of `qcaret.jar`: Java version check, target selection, SWT natives, nested-jar class loader |
| `qcaret-core/` | Threads, paths and atomic writes, log-safe text; the document engine packages to come; no SWT, no logging dependency |
| `qcaret-app/` | Command line, commands and menus, controllers, view contracts, settings, theme, About facts; no SWT |
| `qcaret-ui-swt/` | SWT views: main window, dialogs, theme service; the toolbar icon set (`src/icons/*.svg`), rendered to PNG at build time |
| `qcaret-launcher/` | Composition, logging configuration, architecture rules |
| `qcaret-dist/` | Universal jar, launchers, runtimes and archives; icons |
| `qcaret-bench/` | Benchmarks; not shipped |
| `config/` | Checkstyle, SpotBugs and find-sec-bugs configuration |
| `docker/`, `compose.yaml`, `scripts/` | Container build |
| `.github/` | CI workflows, Dependabot and the scripts the CI steps run |
| `tools/icons.sh` | Renders the application icon from `qcaret-dist/src/icons/*.svg` |

Each module's `README.md` describes its design and contracts. `NOTICE` and
`qcaret-dist/src/dist/THIRD-PARTY.txt` list the licenses of shipped components.
