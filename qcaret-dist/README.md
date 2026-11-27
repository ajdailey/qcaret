# qcaret-dist

Builds the universal jar and zip, the six per-target bundles, the Windows
installers, the Linux packages and the launchers. Packaging is
`src/build/dist.xml`, run by maven-antrun-plugin at `package` after the
dependency plugin stages the jars and launch4j builds `qcaret.exe`. Archives go
to the top-level `dist/`, which no build step deletes.

`qcaret.exe`, the archives, installers and packages need an x86-64 Linux build
host; the `archive-host` profile (`os.name` Linux, `os.arch` amd64) turns them on. On
any other host the module builds `target/qcaret.jar` only, and `SmokeTest`
runs against it.

## Universal jar

`target/qcaret.jar` holds the bootstrap classes at the root, the application
jars under `META-INF/qcaret/lib/`, the six SWT fragments unmodified under
`META-INF/qcaret/swt/<target>.jar`, a SHA-256 for every native,
`index.properties` (layout in `qcaret-bootstrap/README.md`), and `LICENSE`,
`NOTICE` and `THIRD-PARTY.txt` in `META-INF/`. The maven-shade
plugin cannot merge the fragments, which share class names.

`qcaret-<version>.zip` holds `qcaret/lib/qcaret.jar`, `qcaret/bin/qcaret`,
`qcaret/qcaret.exe`, `qcaret/qcaret.cmd`, both file-association sets below, `LICENSE`, `NOTICE` and
`THIRD-PARTY.txt`.

## Bundles

Each bundle carries the same universal jar plus a runtime for its target, so
every target runs the jar that was tested.

| Target | Archive | Layout |
|---|---|---|
| Linux | `-portable.tar.gz` | `qcaret/bin/{qcaret, install-desktop-entry}`, `qcaret/share/{qcaret.desktop, qcaret.svg}`, `qcaret/lib/qcaret.jar`, `qcaret/lib/swt/`, `qcaret/runtime/`, `qcaret/portable.txt` |
| Windows | `-portable.zip` | `qcaret/qcaret.exe`, `qcaret/qcaret.cmd`, `qcaret/register-associations.cmd`, `qcaret/lib/qcaret.jar`, `qcaret/lib/swt/`, `qcaret/runtime/`, `qcaret/portable.txt` |
| macOS | `.zip` | `QCaret.app/Contents/{Info.plist, MacOS/QCaret, app/QCaret.cfg, app/lib/qcaret.jar, runtime/, Resources/QCaret.icns, Resources/swt/}` |

`lib/swt/` holds the target's natives, `about.html` and `about_files/`,
unzipped from the same fragment the jar carries, so the bootstrap verifies
them against the jar's digests and never writes there. The macOS bundle has no
`lib/swt/`: rcodesign re-signs every Mach-O file in the bundle, which changes
the jnilib bytes, so the bootstrap extracts the natives from the jar into the
user cache on first start, with Eclipse's signatures intact. The SWT notices
are in `Resources/swt/`.

`portable.txt` (from `src/dist/`, CRLF in the Windows zip) or `qcaret.conf` in
`qcaret/` selects portable mode on Linux and Windows; on Linux either in
`qcaret/bin/`, beside the script, also does, and `qcaret/` wins when both
directories hold one. Modes are in the root `README.md` under "Settings
directory". Only the archives hold the marker: `posix-bundle` adds it to the
tree for `tar` and deletes it before the packages are built, and the Windows
zip adds it from outside the tree, which the setup lists whole. No archive
holds `qcaret.conf`, which QCaret writes at its first save, so unpacking a
newer archive over a portable folder keeps the settings.

The runtime is linked by the build JDK's `jlink` from the target JDK's jmods
(Temurin 21.0.13.1+2, and 21.0.12.1+1 for windows-aarch64, which has no
21.0.13.1+2 build; downloaded once into `qcaret.cache.dir`, default
`~/.cache/qcaret-build`, and verified by SHA-256) with
`java.base,java.logging,java.management,java.naming,java.xml,jdk.charsets,jdk.localedata,jdk.unsupported,jdk.zipfs`,
`--strip-java-debug-attributes`, `--no-header-files` and `--no-man-pages`.
jlink on a POSIX host writes the `legal/<module>/` notices as links into
`legal/java.base/`; the `runtime` macro replaces them with copies for every
target before signing, because Ant's `zip` stores a link as a regular file and
the macOS seal must match what unzips. The copies also keep the notices for an
extractor that drops links.
`jdk.charsets` carries the legacy encodings (Big5, EUC-JP, EUC-KR, GBK,
Shift_JIS, windows-31j and others) that `java.base` lacks on Windows and
macOS. `jdk.localedata` carries the date and number formats of every locale
but English, used by the Workspace Browser's Size and Modified texts and Insert
Date and Time; it adds 28.9 MB to the linux-x86_64 runtime (70.4 to 99.3 MB,
measured on Temurin 21.0.13.1+2). `java.management` carries the heap pool figures the memory
indicator reads. `jdeps` reports `java.desktop` too, used only by `SWT_AWT`, so
it is left out. `--strip-debug` is not used: it runs the host `objcopy` on target
binaries and fails across targets.

The `runtime` macro deletes `bin/keytool` (`keytool.exe` on Windows), which
`java.base` adds and QCaret never runs, on every target and before the macOS
bundle is signed.

The `windows-bundle` macro deletes `ucrtbase.dll` and every `api-ms-win-*.dll`
from `runtime/bin/` on both Windows targets: Windows 10 and later load the
system Universal C Runtime even when an app-local copy exists. The bundle keeps
`msvcp140.dll`, `vcruntime140.dll` and, on windows-x86_64, `vcruntime140_1.dll`.

## File associations

| Target | File | Effect |
|---|---|---|
| macOS | `src/macos/Info.plist` `CFBundleDocumentTypes` | QCaret is an Alternate editor for text, source, XML, JSON, YAML and Markdown; files arrive as `SWT.OpenDocument` |
| Linux | `src/linux/install-desktop-entry`, `src/linux/qcaret.desktop` | Per user: writes the entry, with this tree's `bin/qcaret` in `Exec`, and the icon under `XDG_DATA_HOME`; `--uninstall` removes them |
| Windows | `src/windows/register-associations.cmd` | Per user, `HKCU` only: adds `QCaret.File` to each listed extension's `OpenWithProgids`; `/u` removes it |

None takes a default away from the user's current program. Each opens the file
through the normal launch, so a running instance receives it through the
handoff. The Windows setup and the Linux packages register the same lists
([Installers and packages](#installers-and-packages)).

## Installers and packages

Each is built from its target's bundle tree after the bundle archive, by the
`windows-installer` and `linux-packages` macros. `InstallerArt.java`, run with
jsvg at build time, renders the hicolor PNGs (16 to 512 px) and the NSIS
welcome (164 x 314) and header (150 x 57) bitmaps from `src/icons/qcaret.svg`
into `target/installer/art`; the images hold no text, so host fonts do not
change them.

| Output | Tool | Contents |
|---|---|---|
| `qcaret-<version>-windows-<arch>-setup.exe` | `makensis` 3.09 from Ubuntu 24.04's `nsis` and `nsis-common` packages, unpacked with `dpkg-deb`; `src/windows/qcaret.nsi` | The bundle tree, installed per machine, per user or portable |
| `qcaret-<version>-linux-<arch>.deb`, `.rpm` | `nfpm` 2.48.0; `src/linux/nfpm.yaml` | `/opt/qcaret` (the bundle tree), `/usr/bin/qcaret` linking to `/opt/qcaret/bin/qcaret`, `/usr/share/applications/qcaret.desktop`, `/usr/share/icons/hicolor/*/apps/qcaret.png` and `scalable/apps/qcaret.svg` |

### Windows setup

`qcaret.nsi` builds an `amd64-unicode` installer for both Windows targets; on
arm64 it runs under x64 emulation and installs the arm64 runtime. It runs with
`RequestExecutionLevel highest`: an administrator gets the UAC prompt at
start, and for anyone else the all-users choice is disabled.

| Choice | Folder | Writes |
|---|---|---|
| All users (`/MACHINE`) | `$PROGRAMFILES64\QCaret` | `uninstall.exe`; `HKLM` Installed apps entry (`DisplayIcon` `qcaret.exe`, publisher, version, size), Start menu `QCaret.lnk` for all users, `QCaret.File` ProgID listed in the `OpenWithProgids` of each extension `register-associations.cmd` lists, and `Applications\qcaret.exe` |
| Current user (`/USER`) | `$LOCALAPPDATA\Programs\QCaret` | The same under `HKCU` and the user's Start menu |
| Portable (`/PORTABLE`) | `$DOCUMENTS\QCaret` by default | `portable.txt`, the archives' marker, unless one exists; nothing else |

- The license page shows `LICENSE` (Apache-2.0) with a Next button and asks
  for no acceptance; the line above the text reads "Press Page Down to see the
  rest of the license."
- The finish page has Run QCaret, checked, and Create a desktop shortcut,
  unchecked and hidden for portable; `/DESKTOP` selects the shortcut in a
  silent install. The shortcut goes on the common desktop for all users and on
  the user's desktop for the current user. Setup never removes one; the
  uninstaller does, retrying each shortcut for up to 10 s while another
  process holds it open. Run QCaret executes `explorer.exe <folder>\qcaret.exe`:
  Explorer hands the file to the running shell, which starts it with the
  signed-in user's unelevated token, so an all-users install started as
  administrator does not run QCaret elevated. A silent install starts nothing.
- An existing install sets the default folder and the default choice. A
  regular install in another folder of the same scope is uninstalled first.
- When `lib\qcaret.jar` exists, setup deletes `lib\` and `runtime\` before
  copying, so an upgrade leaves no file of the older version. Settings,
  `logs\`, `portable.txt` and `qcaret.conf` in a portable folder stay.
- The directory page refuses a regular install into a folder holding
  `portable.txt` or `qcaret.conf`, which would run portable, and a portable
  copy into a folder holding `uninstall.exe`, under `$PROGRAMFILES64`,
  `$PROGRAMFILES32` or `$WINDIR`, or one it cannot write. Silent setup exits 2
  instead.
- `qcaret.nsi` repeats the extension list of `register-associations.cmd`;
  `check-installer.ps1` reads the `.cmd` list and fails on an extension setup
  did not register. Neither list holds `.bat` or `.cmd`: with `QCaret.File` in
  their `OpenWithProgids`, ShellExecute fails to run a batch file (measured on
  both Windows CI runners).
- `NsisFiles.java` writes `files.nsh`: a `File` per bundle file and a
  `Delete` and `RMDir` per file and folder for the uninstaller, sorted by
  name. The uninstaller removes exactly those, `uninstall.exe`, the shortcuts
  and the registry keys; the settings under `%APPDATA%\QCaret` stay.
- The file properties carry the version, `QCaret` as company and the
  copyright. The installer and uninstaller use `qcaret.ico`.
- Signing: `qcaret.exe` is in the bundle tree before the macro runs, so a
  signing step goes between `exe` and the bundles. `-DSIGN_UNINSTALLER=<cmd>`
  and `-DSIGN_INSTALLER=<cmd>` turn on `!uninstfinalize` and `!finalize`,
  which run the command on the file; dist.xml passes neither.

### Linux packages

The package version is the project version with `-` replaced by `~`
(`0.2.0-rc1` as `0.2.0~rc1`, release `1`), so a release candidate sorts before
its release. The `.deb` depends on `libgtk-3-0t64 | libgtk-3-0`, the `.rpm`
on `gtk3`. The packaged desktop entry is `src/linux/qcaret.desktop` with
`Exec=/opt/qcaret/bin/qcaret %F`. Its `StartupWMClass=QCaret` is the main
window's `WM_CLASS` under GTK 3 (measured with `xprop`, and checked by CI), so
the GNOME dock shows the QCaret icon; its `MimeType` list gives Open With. The
package scripts are empty: the icon cache and desktop database update through
the distributions' file triggers.

## Reproducible output

Every archive entry, and every entry of `qcaret.jar`, is dated
`project.build.outputTimestamp` (parent POM; set it to the release date when
the version changes):

- `jar` and `zip` take `modificationtime` from `archive.time`, the instant's
  UTC wall time. Ant writes DOS entry times in the host time zone, so every
  host then writes the same fields.
- The `-portable.tar.gz` bundles are written by GNU `tar` with entries sorted
  by name, one mtime, owner and group 0, and modes set by `chmod` beforehand,
  then compressed by Ant's `gzip`. Ant's `tar` has no `modificationtime`.
- `qcaret.jar` is byte-identical across build hosts under one JDK: `dist.xml`
  rewrites the native digests to LF and sorts the `index.properties` lists by
  name, case-sensitive.
- The installers and packages store file times. Before `nfpm` and `makensis`
  run, the `date-tree` macro dates the bundle tree, the packaged desktop entry
  and the installer bitmaps `project.build.outputTimestamp`; `nfpm.yaml` sets
  that time and umask 022 for the files outside the tree. `NsisFiles` sorts the
  installer's file list, and `nfpm` sorts its own.
- launch4j's `ld` stamps `qcaret.exe` with the link time. `PeTimestamp.java`
  rewrites the COFF time stamp and the PE checksum to the values `ld` writes
  with `SOURCE_DATE_EPOCH` set, which cannot reach `ld` inside the Maven JVM.

The jlink runtimes and the ad-hoc rcodesign signature hold no build time.
jlink runs under `LC_ALL=C.UTF-8`: under a non-UTF-8 locale it writes a
different `lib/modules`. `.github/scripts/check-reproducible.sh` builds twice,
the second time under another time zone, umask and locale, and compares the
SHA-256 of every archive, installer, package, `qcaret.exe` and module jar.

## Release artifacts

`package -Psbom org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom` writes
`dist/qcaret-<version>-sbom.cdx.json`, a CycloneDX aggregate SBOM of the modules
and their compile and runtime dependencies (`qcaret-bench` excluded). The goal
runs once, in the parent, after every module has packaged, and reads the module
jars from the reactor. Do not bind it to `package`: the parent's `package` runs
before the modules build, so the goal requests their jars from Maven Central. The
`sbom` profile here adds the six SWT fragments as dependencies so the SBOM
lists them; `stage-lib` excludes `org.eclipse.platform`, so the archives are
unchanged. In CI, `.github/scripts/sbom-components.sh` adds the Temurin
releases `dist.xml` downloads, launch4j's header and NSIS.

A pushed `v*` tag ends in a draft GitHub Release holding the seven archives,
the two installers, the four packages, `SHA256SUMS` and the SBOM, a pre-release when the tag has a suffix
(`v0.2.0-rc1`); the maintainer reviews and publishes it. Steps and
recovery are in the root [README](../README.md#releases).

## Launchers

| Launcher | Behavior |
|---|---|
| `bin/qcaret` (POSIX sh) | Java from `<home>/runtime`, else `$JAVA_HOME`, else `PATH`; `--enable-native-access=ALL-UNNAMED`, `-XX:MaxRAMPercentage=50`, plus `-XstartOnFirstThread` on Darwin. Follows `$0` symlinks with plain `readlink`. `--gtk4` before `--` exports `SWT_GTK4=1` and is dropped from the arguments; after `--` it passes through as a file name. Runs only `uname` and `readlink` besides Java |
| `qcaret.exe` (launch4j 3.50, `gui`) | Runs `lib\qcaret.jar` with a Java 21+ found in `runtime`, `%JAVA_HOME%`, then `%PATH%`; 64-bit only; `--enable-native-access=ALL-UNNAMED`, `-XX:MaxRAMPercentage=50`. The launch4j header is a 32-bit x86 program that starts `javaw.exe` as a child process; on Windows arm64 it runs under x86 emulation and starts the arm64 runtime, which the CI `bundle` job checks. The header reads its command line in the ANSI code page with best-fit mapping and starts Java with `CreateProcessA`: a character outside the code page arrives as `?` or as an ASCII look-alike (U+FF41 as `a`, U+0131 as `i`, U+FF02 as `"`, U+FF0A as `*`), quoted or not (measured, below) |
| `qcaret.cmd` (cmd.exe) | Runs `lib\qcaret.jar` with `runtime\bin\javaw.exe`, else `%JAVA_HOME%\bin\javaw.exe`, else `javaw.exe` on `PATH`, with no version or 64-bit check; `--enable-native-access=ALL-UNNAMED`, `-XX:MaxRAMPercentage=50`; then the arguments. Started as `cmd.exe /c ""<qcaret.cmd>" ..."` (Explorer, a shortcut, PowerShell, another program), it takes the arguments from `%cmdcmdline%` as the caller wrote them, before that cmd.exe's parse, and ends that cmd.exe with `exit`, so a command after an unquoted `&` never runs. From a command prompt or a batch file it reads `%*` back from an echoed `rem` line through a file in `%TEMP%`; where that fails, as under `cmd /q`, it runs nothing and prints `qcaret.cmd could not read its arguments through "<file>".` with exit 1. A name outside the ANSI code page arrives intact this way under console code page 65001 (measured on CI). Either way the script sets `QCARET_CMDLINE` to the argument text through delayed expansion, which follows parsing, and starts Java with `-Dqcaret.launcher=cmd` and no arguments; the bootstrap splits the variable by the C runtime rules (`qcaret-bootstrap`). Java reads the environment in UTF-16, so a name outside the ANSI code page arrives intact and the Java launcher expands no wildcard. The calling cmd.exe acts on an unquoted `\|`, `<` or `>` before the script runs; no file name holds one. Every line starts with `@`: a calling batch keeps its echo state, and the prompt path restores echo on after its read-back. `start "" /b` returns at once and gives Java the console's output: `-v`, `-h` and errors print when it is redirected or piped, and not in an interactive console, as with `qcaret.exe` (measured on Windows 11 26H2). Once its window opens QCaret closes both handles (`CmdStreams`), so a caller reading a pipe reaches its end while QCaret runs. A double-clicked `qcaret.cmd` leaves no console open (same host). Without Java it prints a message, pauses and exits 1. Neither launcher starts from an install path holding `;`, which splits the `-jar` class path. It does not read `qcaret.l4j.ini` |
| `QCaret.app/Contents/MacOS/QCaret` | The `jpackageapplauncher` binary from the target JDK's `jdk.jpackage` module. It reads `Contents/app/QCaret.cfg` and starts `Contents/runtime` with `-XstartOnFirstThread`, `--enable-native-access=ALL-UNNAMED` and `-XX:MaxRAMPercentage=50`; the layout copies jpackage's; the CI `bundle` job starts it on both macOS runners |

### Names outside the ANSI code page

Measured on `windows-2025` and `windows-11-arm`, ANSI code page 1252, with a
CJK name and a fullwidth `a` (U+FF41) beside the decoys `zz.txt` and `a.txt`.
Explorer's drop and ShellExecute pass the long name, unquoted unless it holds a
space; "Open with" quotes it.

| Launch | `qcaret.exe` hands Java | QCaret | `qcaret.cmd` |
|---|---|---|---|
| Drop or ShellExecute, CJK | `??.txt`, unquoted | The Java launcher expands `??.txt` to every matching file; QCaret opens no file named at that launch and shows a message naming the pattern | Opens the name |
| Open with or quoted, CJK | `"??.txt"` | Not opened; the message names it | Opens the name |
| Any form, U+FF41 | `a.txt` | Opens the decoy `a.txt` | Opens the name |

QCaret cannot recognize a look-alike, since `javaw` never receives the
original name. Through `java -jar` the Java launcher converts without
look-alikes, so U+FF41 arrives as `?`.

`rcodesign` 0.29.0 ad-hoc signs each `QCaret.app` on Linux with
`src/macos/qcaret.entitlements` (`allow-jit`,
`allow-unsigned-executable-memory`, `disable-library-validation`), which the
JVM needs under the hardened runtime. rcodesign cannot sign a shell script as
the main executable, which is why the macOS launcher is the jpackage binary.
The `bundle` CI job runs `codesign --verify
--deep --strict` on the unzipped `QCaret.app`. The bundles are not notarized.

## Icons

`src/icons/qcaret.svg` is the OS-facing icon (window, taskbar, Dock, `.exe`,
`.icns`); `qcaret-light.svg` is for in-app use with the light palette.
`tools/icons.sh` renders `qcaret.ico`, `QCaret.icns` and the PNGs in
`qcaret-ui-swt` from them; the build uses the committed outputs. The package
icons and installer bitmaps are rendered at build time
([Installers and packages](#installers-and-packages)).

## Testing

`SmokeTest` (tag `swt`) runs at `integration-test` against the built jar and,
on linux-x86_64, the extracted bundle:

| Case | Checks |
|---|---|
| `java -jar qcaret.jar -v` | Prints the manifest version as its first line |
| Jar content | Digests hold no CR and each `natives.` list is in name order |
| Jar notices | `META-INF/` holds `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` |
| `java -jar qcaret.jar` | Opens a 900 x 600 window at 50,40 (within one point at a fractional zoom, the location clamped into the work area as placement clamps it, and not checked under GTK 4, which cannot move a window) and writes no `logs` folder |
| Linux bundle with `-l`, `PATH` holding only `uname` and `readlink` | Reports its own `runtime/` as `java.home`, opens the same window, logs `First paint of file text` (the line `qcaret-bench` times), and after SIGTERM leaves one `qcaret-<pid>.log` holding the shutdown hook's line and no other file in `logs` |
| Linux bundle runtime | `runtime/release` lists `jdk.charsets`, `jdk.localedata` and `java.management`; `runtime/bin` holds no `keytool`; every encoding in `SmokeTest.ENCODINGS` is supported (`CharsetProbe`) |
| Linux bundle `bin/qcaret`, stub `runtime/bin/java` | `qcaret -m --gtk4 -- --gtk4` passes `SWT_GTK4=1`, the JVM options, `-jar <home>/lib/qcaret.jar`, `-m`, `--`, `--gtk4` |

The module's other test classes and probes:

| Class | Covers |
|---|---|
| `DesktopEntryTest` (POSIX) | `install-desktop-entry` run from install folders whose names hold a space, `%`, `\`, `"`, `$`, a backtick, `&`, `\|`, `;`, `'` and `(` writes an `Exec` that decodes, by the Desktop Entry rules, to the folder's `bin/qcaret` and `%F`, and that `desktop-file-validate` accepts where installed; `--uninstall` removes the entry |
| `CharsetProbe` | Run by the bundle's `java`; fails unless every encoding in `SmokeTest.ENCODINGS` is supported |
| `LauncherProbe` | Run as `lib\qcaret.jar` under `qcaret.cmd` by `check-bundle.ps1`; writes `java.home`, the class path, the JVM options and the arguments, as the bundle's `CmdArguments` reads them, to the file `QCARET_PROBE_OUT` names |

### CI bundle check

The CI `bundle` job runs each archive on its own runner through
`.github/scripts/check-bundle.sh` (Linux, macOS) or `check-bundle.ps1`
(Windows). A Linux or Windows archive must hold `portable.txt` and no
`qcaret.conf`. For the target's bundle and then the universal zip:

| Check | Linux, macOS | Windows |
|---|---|---|
| Runtime | `os.arch`, `jdk.charsets`, `jdk.localedata`, `java.xml`, `java.management`, no `bin/keytool` | The same, with no `keytool.exe`, plus the `javaw.exe` child's image and PE machine |
| Log read | `-d 1` on stderr | `-l`, `logs\qcaret.log`: `javaw` output is not captured |
| Started | `Main window open`, `Grammars read: 0 skipped`, no `Unhandled failure` or `UI task failed` | The same |
| Handoff | A second `bin/qcaret` or `QCaret` exits 0; the primary logs `Handoff of 1 files taken` | The same, the second launch through `javaw.exe`: `qcaret.exe` exits 0 whatever the child's status (measured: `qcaret.exe --bogus` exits 0) |
| Native access | No `WARNING: A restricted` in either launch's output | The child's command line holds `--enable-native-access=ALL-UNNAMED`; two more handoffs through `java.exe` with standard error captured: with the flag no `restricted method`, without it the warning, which shows the foreground grant linked |
| `qcaret.cmd` | None | Started and Handoff through `qcaret.cmd`. In the bundle, over `LauncherProbe`: from folders named `with space` and `amp & pct 100%`, the folder's `runtime`, its `lib\qcaret.jar`, `--enable-native-access=ALL-UNNAMED`, `-XX:MaxRAMPercentage=50`, `-Dqcaret.launcher=cmd` and 11 quoted arguments holding a space, `&`, `^`, `%`, `!`, `(`, `)`, `'` or nothing; through ShellExecute, as Explorer drops them, unquoted `<folder>\a&mark`, `c^d.txt`, `e!f.txt`, `g%h%.txt` with `h` defined, `(p).txt`, `café.txt` and a quoted `"x y&mark.txt"`; at a command prompt, `a^&mark`, `c^^d.txt`, `e!f.txt`, `g%q9z%.txt`, `i^\|mark`, `k^>l.txt`, `"x&y.txt"`, `(p).txt` and the 11 quoted arguments. Each name arrives unchanged and `mark.cmd` never runs; without `runtime`, `JAVA_HOME` before `PATH`, and exit 1 without either; `-v` prints the version |
| Names outside the ANSI code page | None | A CJK name beside a decoy `zz.txt` through `qcaret.exe` and `qcaret.cmd`, unquoted and quoted, by ShellExecute and from a console batch file under code page 65001, with `-l -d 2`: `qcaret.cmd` logs `Command-line file: <the name>`; `qcaret.exe` logs the expansion message unquoted and `Not opened: <folder>\??.txt.` quoted; no log names `zz.txt`. With an instance running, an unquoted `qcaret.exe` launch opens its own window with the message and hands nothing over |
| `call qcaret.cmd` | None | Batch files with echo on and with echo off call `qcaret.cmd` with output captured through a pipe: echo keeps its state after the call, and the pipe ends while QCaret runs |

A failed handoff opens its own window and does not exit, so each script bounds
the second launch at 60 s.

The same job then checks the installers and packages on that runner:

| Script | Checks |
|---|---|
| `check-linux-packages.sh` | Installs the `.deb` with `apt`: version, `/usr/bin/qcaret` link, no `qcaret.conf` or `portable.txt`, `desktop-file-validate`, `StartupWMClass=QCaret`, every icon. Runs `check-bundle.sh` with `QCARET_HOME=/opt/qcaret` through `/usr/bin/qcaret`, which also requires a main window with `WM_CLASS` `"QCaret", "QCaret"`. Removes the package and checks the files are gone. Then, in `fedora:44` pinned by digest: `dnf install` of the `.rpm`, `rpm -V`, `qcaret -v`, no `qcaret.conf` or `portable.txt`, `dnf remove` |
| `installer-screenshots.ps1` | Walks the setup's pages by window message (all-users install with the desktop shortcut ticked, its uninstaller, a portable install) and saves each with `PrintWindow` to `installer-screenshots\`, uploaded as `installer-screenshots-<target>`. After each install an `explorer.exe` must have started `qcaret.exe`, and QCaret must have the shell's token elevation (0 for an unelevated shell; the hosted runners' shell is elevated); the all-users walk must write the common desktop shortcut and its uninstaller remove it, and the portable walk writes none |
| `check-installer.ps1` | For `/MACHINE` and then `/USER`: the Installed apps entry, the shortcut target, no desktop shortcut, `QCaret.File` in the `OpenWithProgids` of every extension `register-associations.cmd` lists with each default handler unchanged, a `.cmd` that ShellExecute still runs, no `qcaret.conf` or `portable.txt`, no QCaret process from the folder 5 s after setup exits; `check-bundle.ps1` with `QCARET_HOME` set to the installed folder; an install over it with `/DESKTOP` removes a stale runtime file, adds no `qcaret.conf`, and writes the shortcut to that scope's desktop only; a silent uninstall removes the folder, shortcuts and keys and keeps `%APPDATA%\QCaret`. Then `/PORTABLE /DESKTOP`: a one-line `portable.txt` and no `qcaret.conf`, no registry entry, no Start menu or desktop shortcut, no QCaret started, QCaret writes its `-l` log in the folder, and an install over it keeps `portable.txt`, `qcaret.conf` and `logs\`. Exit 2 for `/USER` into the portable folder, into a folder holding only `qcaret.conf`, and for `/PORTABLE` under Program Files |
