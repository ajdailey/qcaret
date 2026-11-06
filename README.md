<picture>
  <source media="(prefers-color-scheme: dark)" srcset="qcaret-dist/src/icons/qcaret.svg">
  <img src="qcaret-dist/src/icons/qcaret-light.svg" alt="QCaret logo" width="96" height="96">
</picture>

# QCaret

QCaret is a cross-platform programmer's text editor, written in Java 21 with
SWT. It runs no macros, scripts or plugins.

Version 0.1.0 is in development. The current build opens, edits and saves
files in tabs, with undo and redo, word wrap, line numbers and multiple carets
(painted; created by Alt+click), encoding and line-ending conversion, a
read-only view of binary files, a status bar, the Help menu, its remembered
window placement, syntax highlighting for 35 languages and 12 file formats
with user grammars and comment commands, multiple carets, column selection
and folding, HyperSearch across files and folders with replace, a workspace
browser with Quick Open, and Go Back and Go Forward. Planned for 0.1.0:
sessions and crash recovery.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/qcaret-dark.png">
  <img src="docs/images/qcaret-light.png" alt="QCaret main window with a highlighted Java file" width="880">
</picture>

`tools/screenshots.sh` regenerates the screenshots under Xvfb from generated
mock files.

## Syntax highlighting

QCaret highlights Plain Text, Java, XML, HTML (with embedded JavaScript and
CSS), JSON, YAML, SQL, Python, JavaScript, TypeScript, C, C++, Shell Script,
PowerShell, Windows Batch, Properties, INI, CSS and Markdown, and the file
formats Dockerfile, Makefile, CMake, TOML, diff and patch, CSV and TSV (one
color per column), log files (levels, timestamps, stack traces), HCL and
Terraform, Protocol Buffers, GraphQL and nginx configuration. The grammar
follows the file name, its extension, or its first line (a shebang, `<?xml`,
`<!DOCTYPE html>`); View > Syntax or a click on the status bar's language
field chooses another, from the last five chosen or from a category submenu.
A line over 10,000 chars shows without colors.
Highlighting runs on worker threads: opening a large file and typing never
wait for it.

User grammars go in `grammars/*.xml` in the settings directory, in the format
`qcaret-core/README.md` describes; one with a built-in grammar's id replaces
it, and its `category` attribute picks its Syntax submenu (User without one).
They are read at startup and on View > Syntax > Reload Grammars, and an
invalid file is skipped with a notice. Edit > Toggle Line Comment (Mod+/) and
Toggle Block Comment (Mod+Shift+/) use the grammar's comment delimiters.

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
digests.

Builds are reproducible: with the same JDK, two builds of one commit write
byte-identical jars, `qcaret.exe` and archives, every entry dated
`project.build.outputTimestamp` from the parent POM.
`.github/scripts/check-reproducible.sh` builds twice and compares SHA-256.

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

`.github/workflows/ci.yml` runs on every push to `main`, every pull request,
every pushed `v*` tag and on demand, with Temurin 21.0.12+8:

| Job | Runners | Does |
|---|---|---|
| `test` | `ubuntu-24.04`, `ubuntu-24.04-arm`, `windows-2025`, `windows-11-arm`, `macos-15-intel`, `macos-15` | `./mvnw -B verify` on each target natively, SWT tests included (Linux under `xvfb-run`) |
| `test-gtk4` | `ubuntu-24.04` | The SWT modules' tests with `SWT_GTK4=1`; gating, with a test that hits a known SWT GTK 4 defect skipped there and the issue named |
| `package` | `ubuntu-24.04` | `./mvnw -B package -Pall-targets,sbom`; uploads the seven archives and the SBOM as `qcaret-archives`, kept 90 days for a tag and 14 otherwise |
| `bundle` | the six `test` runners | Unpacks the target's bundle, checks the runtime's `os.arch`, and starts the bundle's launcher until the main window opens; then starts the universal zip's launcher, which has no runtime, with setup-java's JDK |
| `release` | `ubuntu-24.04` | `v*` tags only, after every `test`, `test-gtk4` and `bundle` job passes: runs `check-reproducible.sh`, writes `SHA256SUMS` over the `package` job's archives, checks the rebuild against it, and creates a draft GitHub Release |

On Windows the `bundle` job starts `qcaret.exe`, finds the `javaw.exe` child
running `qcaret.jar`, and passes only when the child's image is the bundle's
`runtime\bin\javaw.exe` with the target's PE machine type (`ARM64` on
`windows-11-arm`); the universal zip's `qcaret.exe` must start the JDK's
`javaw.exe`. On macOS it also runs `codesign --verify --deep --strict`
on `QCaret.app`. The `package` job caches the JDK archives and `rcodesign`
under `~/.cache/qcaret-build`, apart from the Maven cache, keyed on
`qcaret-dist/src/build/dist.xml`; the `release` job shares that cache.

The draft Release carries the seven archives the `bundle` jobs ran,
`SHA256SUMS` and `qcaret-<version>-sbom.cdx.json`, with GitHub's generated
release notes. To release:

1. Push the tag: `git tag v<version> && git push origin v<version>`.
2. Review the draft on the repository's Releases page.
3. Publish it there, or with `gh release edit v<version> --draft=false`.

A bad draft is fixed by deleting the draft and the tag
(`gh release delete v<version> --cleanup-tag`) and tagging again. Only the
`release` job has `contents: write`.

`-Psbom` writes the CycloneDX SBOM to `dist/`: every compile and runtime
dependency of the shipped modules, plus the six SWT fragments `qcaret.jar`
carries. It leaves out build-only tools, `qcaret-bench`, the Temurin runtimes
and launch4j's header.

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
qcaret [options] [[+<line>[:<column>]] file]...
java -jar qcaret.jar [options] [[+<line>[:<column>]] file]...
```

| Option | Effect |
|---|---|
| `-h`, `--help` | Print usage and exit 0 |
| `-v`, `--version` | Print `qcaret <version>` and exit 0 |
| `-d`, `--debug <level>` | stderr log level: `1` INFO, `2` DEBUG, `3` and above TRACE, or `error`, `warn`, `info`, `debug`, `trace`; WARN when absent. Show Log (INFO) and the `-l` file (DEBUG) take it when finer. It applies to QCaret's own loggers; the Java runtime's stay at INFO |
| `-l`, `--log` | Also write the log at DEBUG to `logs/qcaret.log` in the settings directory, for this run; a process other than the running instance writes `logs/qcaret-<pid>.log` |
| `-m`, `--multi` | Run as an independent instance: no handoff, and the window placement is not saved |
| `-n`, `--new-window` | Open in a new window of the running instance, placed by any `--window-*` option |
| `-r`, `--read-only` | Open the following files read-only; also applies to a file already open |
| `-e`, `--encoding <name>` | Open the following files in this encoding; a file already open keeps its own |
| `+<line>[:<column>]` | Place the caret in the next file, counting from 1; also applies to a file already open |
| `--session`, `--no-session` | Restore the previous session, or skip it; by default it restores when no file is given. A launch that hands off to a running instance ignores both |
| `--diff <a> <b>` | Open the read-only comparison of two files |
| `--window-width <px>`, `--window-height <px>` | Client-area size, each independently; overrides the remembered size for this run and is not saved back; default 1024 x 768 |
| `--window-x <px>`, `--window-y <px>` | Window position, as above; ignored on Wayland, where the compositor places windows |
| `--settings <dir>` | Settings directory, created owner-only; holds `qcaret.conf` and QCaret's SWT native cache |
| `--` | End of options |
| `file` | Opened in a tab; a missing file opens an empty document that saves to that path. Without a file, an untitled document opens |

Options combine: `-ml`, `--opt=value`, `-d2`. An unknown option or a bad value
prints a message to stderr and exits 1; an SWT start failure, such as missing
GTK libraries, exits 3. After `--`, a `+<line>` is a file name.

Without `-m`, a launch hands its files to the instance running on the same
settings directory and exits 0. Any failure opens the files in the launch's own
window. A file another window holds selects that tab, so an instance holds one
buffer per file.

## Windows

File > Quit asks about unsaved documents in every window. Closing a window asks
about its own and leaves the others open; closing the last one quits. Window >
New Window opens another window. Settings, the theme, the key bindings,
grammars and Recent Files are shared; each window has its own tabs, find bar,
workspace folder and search results.

Drag a tab out of every QCaret window to open it in a new window, or onto
another window's tab strip to dock it at that position; Window > Move to New
Window and the tab menu do the first without a drag. The document moves live:
undo history, unsaved changes, carets, scroll, folds, bookmarks and its recovery
file go with it, and nothing is read again. A tab with a save, reload or conversion running does
not move. Search results stay in their window and still lead to the moved tab.

File associations: on Linux run `bin/install-desktop-entry`; on Windows run
`register-associations.cmd`, each per user; the macOS bundle declares its
document types itself. Details are in `qcaret-dist/README.md`.

## Large files

A file opens only when its estimated memory cost fits the 60% of the maximum
Java heap that documents share; otherwise QCaret refuses it unread and names
the file's size, the memory it needs and the `-Xmx` value to start with. The
estimate is 3 bytes of heap per file byte for a file whose first 64 KiB are
ASCII and 5 bytes otherwise, held during the load; an open document keeps
about twice its text. The launchers set the maximum heap to half of the
physical memory (`-XX:MaxRAMPercentage=50`). To raise it, run the jar with
the bundle's runtime and `-Xmx`, for example
`runtime/bin/java -Xmx8g -jar lib/qcaret.jar` (on Windows, add `-Xmx8g` as a
line in `qcaret.l4j.ini` next to `qcaret.exe`).

Largest single file that opens, measured on Linux x86_64 with nothing else
open (loads at the budget limit completed without an out-of-memory error):

| Maximum heap | ASCII text | UTF-8 text with non-ASCII chars |
|---|---|---|
| 512 MiB | 102 MiB | 61 MiB |
| 1 GiB | 204 MiB | 122 MiB |
| 2 GiB | 409 MiB | 245 MiB |
| 8 GiB | 1.6 GiB | 983 MiB |

The 8 GiB row is the same 20% and 12% of the heap, computed, not measured. No
file over 2 GiB opens.

These operations run on the UI thread, and the window does not respond while
they apply (measured on Linux under Xvfb, one million lines):

| Operation | Time |
|---|---|
| Theme switch or font size change (View > Zoom): every open view's per-line state is reset | About 1 s per view; a split document has two |
| Sort, Remove Duplicate Lines, Trim Trailing Whitespace, line-ending conversion: the computed text replaces the document's (busy cursor) | Sort: 1.2-2.0 s for the apply, after the edit is computed on a worker; the others not measured |

## Network access

QCaret makes no outbound network connections. Its only socket is a loopback
endpoint that passes files from a second launch to the running instance. Help >
Report an Issue is the one command that opens a web address, the project's issue page in the system browser, and
only when the user picks it. Help > User Guide opens a copy of the guide page
written to the settings directory, which loads nothing from elsewhere.

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
| `editor.completionChars` | 0 to 20: word chars typed before completion opens, 0 for never | 0 | Edit > Preferences, Editor |
| `editor.autoClose` | `true`, `false`: typing an opener inserts its closer | `true` | Edit > Preferences, Editor |
| `editor.maxHighlightLineChars` | 1,000 to 1,000,000: a longer line shows without syntax colors | 10000 | Edit > Preferences, Editor |
| `editor.fontSize` | 0 for the platform's default, or 4 to 72 points | 0 | View > Zoom, Edit > Preferences, Appearance |
| `file.pollSeconds` | 1 to 3600: seconds between checks of every open file | 5 | Edit > Preferences, Files |
| `file.autoReloadClean` | `true`, `false`: a changed file reloads a buffer without unsaved changes | `true` | Edit > Preferences, Files |
| `recovery.intervalSeconds` | 5 to 600: seconds between writes of each changed unsaved buffer's recovery file | 30 | Edit > Preferences, Files |
| `recovery.keepDays` | 1 to 365: days a recovery file left by a crash is kept | 30 | Edit > Preferences, Files |
| `session.snapshot` | `true`, `false`: a quit asks nothing and the next start restores unsaved and untitled buffers | `false` | Edit > Preferences, Files |
| `search.maxLineChars` | 10,000 to 100,000,000: HyperSearch and replace skip and count a longer line | 1000000 | Edit > Preferences, Search and Workspace |
| `replace.backupDays` | 1 to 365: days a replace run's backups are kept | 7 | Edit > Preferences, Search and Workspace |
| `workspace.excludedDirectories` | Directory names separated by `;`: the browser, Quick Open's index and directory searches skip them | `.git;.svn;.hg;target;node_modules;build` | Edit > Preferences, Search and Workspace |
| `shortcut.<COMMAND>` | Keys separated by spaces, such as `Mod+Alt+K F7`; `Mod` is Ctrl, or Command on macOS, and `Control` is the macOS Control key; empty for no key | The command's default keys | Edit > Preferences, Keys |
| `search.regex`, `.caseSensitive`, `.wholeWord`, `.hiddenFiles` | The find bar's and HyperSearch's toggles | `false` | The find bar, HyperSearch |
| `search.history.text.N`, `.directory.N`, `.mask.N`, `.replace.N` | Search texts of the find bar and HyperSearch, HyperSearch's directories and include masks, the find bar's replacements; newest first, 20 each | none | The find bar, HyperSearch |
| `syntax.recent` | The last five grammars chosen, ids separated by `;` | none | View > Syntax |
| `dialog.<name>.width`, `.height` | A resizable dialog's last size | none | Closing the dialog |

A value out of range falls back to its default with one warning naming the key.
Every Preferences setting applies to the running window; none needs a restart.
Saving writes only the keys that changed, over a fresh read of the file, so
a second instance's newer values and hand-added keys stay. Keys another command
holds are refused in the dialog; a conflict written by hand is logged and
listed at the end of Help > Keyboard Shortcuts. Light and dark also set the
native controls' theme; Windows applies a change to native controls at the
next start, and macOS native controls follow the system appearance.

### Sessions and recovery

A start without file arguments restores the last session from
`qcaret_session.conf` in the settings directory: every window at its position,
with its tabs, carets, scroll positions, splits, folds, bookmarks, workspace and
search dock with its requests, and Recent Files. A file that does not open is named and skipped. A `-m` instance
never replaces the session. A malformed or unreadable session file, or one over
4 MiB, is ignored with one warning and kept as `qcaret_session.conf.invalid`
before the next save.

Unsaved buffers are written to `recovery/` in the settings directory. After a
crash the next start lists them with the state of each original file: Restore
opens the buffer with its unsaved changes and does not touch the original,
Discard deletes the recovery file, and Later keeps it. A damaged recovery file
is reported and kept.

## Layout

| Path | Contents |
|---|---|
| `qcaret-bootstrap/` | Entry point of `qcaret.jar`: Java version check, target selection, SWT natives, nested-jar class loader |
| `qcaret-core/` | Threads and the heap budget, paths and safe saves, encodings, the rope storage and the document model, grammars and the tokenizer; no SWT, no logging dependency |
| `qcaret-app/` | Command line, commands and menus, controllers, the display projection, view contracts, settings, theme, About facts; no SWT |
| `qcaret-ui-swt/` | SWT views: main window and tabs, the editor over `StyledText`, dialogs, theme service; the toolbar icon set (`src/icons/*.svg`), rendered to PNG at build time |
| `qcaret-launcher/` | Composition, logging configuration, architecture rules |
| `qcaret-dist/` | Universal jar, launchers, runtimes and archives; icons |
| `qcaret-bench/` | Benchmarks; not shipped |
| `config/` | Checkstyle, SpotBugs and find-sec-bugs configuration |
| `docker/`, `compose.yaml`, `scripts/` | Container build |
| `.github/` | CI workflows, Dependabot and the scripts the CI steps run |
| `tools/icons.sh` | Renders the application icon from `qcaret-dist/src/icons/*.svg` |
| `tools/screenshots.sh`, `docs/images/` | README screenshots and the script that writes them |

Each module's `README.md` describes its design and contracts. `NOTICE` and
`qcaret-dist/src/dist/THIRD-PARTY.txt` list the licenses of shipped components.
