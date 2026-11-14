# qcaret-dist

Builds the universal jar and zip, the six per-target bundles and the
launchers. Packaging is `src/build/dist.xml`, run by maven-antrun-plugin at
`package` after the dependency plugin stages the jars and launch4j builds
`qcaret.exe`. Archives go to the top-level `dist/`, which no build step deletes.

`qcaret.exe` and the archives need an x86-64 Linux build host; the
`archive-host` profile (`os.name` Linux, `os.arch` amd64) turns them on. On
any other host the module builds `target/qcaret.jar` only, and `SmokeTest`
runs against it.

## Universal jar

`target/qcaret.jar` holds the bootstrap classes at the root, the application
jars under `META-INF/qcaret/lib/`, the six SWT fragments unmodified under
`META-INF/qcaret/swt/<target>.jar`, a SHA-256 for every native, and
`index.properties` (layout in `qcaret-bootstrap/README.md`). The maven-shade
plugin cannot merge the fragments, which share class names.

`qcaret-<version>.zip` holds `qcaret/lib/qcaret.jar`, `qcaret/bin/qcaret`,
`qcaret/qcaret.exe`, both file-association sets below, `LICENSE`, `NOTICE` and
`THIRD-PARTY.txt`.

## Bundles

Each bundle carries the same universal jar plus a runtime for its target, so
every target runs the jar that was tested.

| Target | Archive | Layout |
|---|---|---|
| Linux | `.tar.gz` | `qcaret/bin/{qcaret, install-desktop-entry}`, `qcaret/share/{qcaret.desktop, qcaret.svg}`, `qcaret/lib/qcaret.jar`, `qcaret/lib/swt/`, `qcaret/runtime/` |
| Windows | `.zip` | `qcaret/qcaret.exe`, `qcaret/register-associations.cmd`, `qcaret/lib/qcaret.jar`, `qcaret/lib/swt/`, `qcaret/runtime/` |
| macOS | `.zip` | `QCaret.app/Contents/{Info.plist, MacOS/QCaret, app/QCaret.cfg, app/lib/qcaret.jar, runtime/, Resources/QCaret.icns, Resources/swt/}` |

`lib/swt/` holds the target's natives, `about.html` and `about_files/`,
unzipped from the same fragment the jar carries, so the bootstrap verifies
them against the jar's digests and never writes there. The macOS bundle has no
`lib/swt/`: rcodesign re-signs every Mach-O file in the bundle, which changes
the jnilib bytes, so the bootstrap extracts the natives from the jar into the
user cache on first start, with Eclipse's signatures intact. The SWT notices
are in `Resources/swt/`.

A `qcaret.conf` in `qcaret/` selects portable mode on Linux and Windows; on
Linux one in `qcaret/bin/`, beside the script, also does, and `qcaret/` wins
when both exist. Modes are in the root `README.md` under "Settings directory".

The runtime is linked by the build JDK's `jlink` from the target JDK's jmods
(Temurin 21.0.12+8, downloaded once into `qcaret.cache.dir`, default
`~/.cache/qcaret-build`, and verified by SHA-256) with
`java.base,java.logging,java.management,java.naming,java.xml,jdk.charsets,jdk.localedata,jdk.unsupported,jdk.zipfs`,
`--strip-java-debug-attributes`, `--no-header-files` and `--no-man-pages`.
`jdk.charsets` carries the legacy encodings (Big5, EUC-JP, EUC-KR, GBK,
Shift_JIS, windows-31j and others) that `java.base` lacks on Windows and
macOS. `jdk.localedata` carries the date and number formats of every locale
but English, used by the Workspace Browser's Size and Modified texts and Insert
Date and Time; it adds 28.8 MB to the linux-x86_64 runtime (68.4 to 97.2 MB,
measured). `java.management` carries the heap pool figures the memory
indicator reads. `jdeps` reports `java.desktop` too, used only by `SWT_AWT`, so
it is left out. `--strip-debug` is not used: it runs the host `objcopy` on target
binaries and fails across targets.

## File associations

| Target | File | Effect |
|---|---|---|
| macOS | `src/macos/Info.plist` `CFBundleDocumentTypes` | QCaret is an Alternate editor for text, source, XML, JSON, YAML and Markdown; files arrive as `SWT.OpenDocument` |
| Linux | `src/linux/install-desktop-entry`, `src/linux/qcaret.desktop` | Per user: writes the entry, with this tree's `bin/qcaret` in `Exec`, and the icon under `XDG_DATA_HOME`; `--uninstall` removes them |
| Windows | `src/windows/register-associations.cmd` | Per user, `HKCU` only: adds `QCaret.File` to each listed extension's `OpenWithProgids`; `/u` removes it |

None takes a default away from the user's current program. Each opens the file
through the normal launch, so a running instance receives it through the
handoff.

## Reproducible output

Every archive entry, and every entry of `qcaret.jar`, is dated
`project.build.outputTimestamp` (parent POM; set it to the release date when
the version changes):

- `jar` and `zip` take `modificationtime` from `archive.time`, the instant's
  UTC wall time. Ant writes DOS entry times in the host time zone, so every
  host then writes the same fields.
- The `.tar.gz` bundles are written by GNU `tar` with entries sorted by name,
  one mtime, owner and group 0, and modes set by `chmod` beforehand, then
  compressed by Ant's `gzip`. Ant's `tar` has no `modificationtime`.
- `qcaret.jar` is byte-identical across build hosts under one JDK: `dist.xml`
  rewrites the native digests to LF and sorts the `index.properties` lists by
  name, case-sensitive.
- launch4j's `ld` stamps `qcaret.exe` with the link time. `PeTimestamp.java`
  rewrites the COFF time stamp and the PE checksum to the values `ld` writes
  with `SOURCE_DATE_EPOCH` set, which cannot reach `ld` inside the Maven JVM.

The jlink runtimes and the ad-hoc rcodesign signature hold no build time.
jlink runs under `LC_ALL=C.UTF-8`: under a non-UTF-8 locale it writes a
different `lib/modules`. `.github/scripts/check-reproducible.sh` builds twice,
the second time under another time zone, umask and locale, and compares the
SHA-256 of every archive, `qcaret.exe` and module jar.

## Release artifacts

`-Psbom` writes `dist/qcaret-<version>-sbom.cdx.json`, a CycloneDX aggregate
SBOM of the compile and runtime dependencies (cyclonedx-maven-plugin
`makeAggregateBom`, run once in the parent, `qcaret-bench` excluded). The
`sbom` profile here adds the six SWT fragments as dependencies so the SBOM
lists them; `stage-lib` excludes `org.eclipse.platform`, so the archives are
unchanged.

A pushed `v*` tag ends in a draft GitHub Release holding the seven archives,
`SHA256SUMS` and the SBOM; the maintainer reviews and publishes it. Steps and
recovery are in the root [README](../README.md#releases).

## Launchers

| Launcher | Behavior |
|---|---|
| `bin/qcaret` (POSIX sh) | Java from `<home>/runtime`, else `$JAVA_HOME`, else `PATH`; `--enable-native-access=ALL-UNNAMED`, `-XX:MaxRAMPercentage=50`, plus `-XstartOnFirstThread` on Darwin. Follows `$0` symlinks with plain `readlink`. Runs only `uname` and `readlink` besides Java |
| `qcaret.exe` (launch4j 3.50, `gui`) | Runs `lib\qcaret.jar` with a Java 21+ found in `runtime`, `%JAVA_HOME%`, then `%PATH%`; 64-bit only; `-XX:MaxRAMPercentage=50`. The launch4j header is a 32-bit x86 program that starts `javaw.exe` as a child process; on Windows arm64 it runs under x86 emulation and starts the arm64 runtime, which the CI `bundle` job checks |
| `QCaret.app/Contents/MacOS/QCaret` | The `jpackageapplauncher` binary from the target JDK's `jdk.jpackage` module. It reads `Contents/app/QCaret.cfg` and starts `Contents/runtime` with `-XstartOnFirstThread`, `--enable-native-access=ALL-UNNAMED` and `-XX:MaxRAMPercentage=50`; the layout copies jpackage's; the CI `bundle` job starts it on both macOS runners |

`rcodesign` 0.29.0 ad-hoc signs each `QCaret.app` on Linux with
`src/macos/qcaret.entitlements` (`allow-jit`,
`allow-unsigned-executable-memory`, `disable-library-validation`), which the
JVM needs under the hardened runtime. rcodesign cannot sign a shell script as
the main executable, which is why the macOS launcher is the jpackage binary.
Before signing, every symlink jlink wrote under `legal/` is replaced by a copy
of its target: `<zip>` stores the target's content, so a signed symlink would
not match the seal after unzipping. The `bundle` CI job runs `codesign --verify
--deep --strict` on the unzipped `QCaret.app`. The bundles are not notarized.

## Icons

`src/icons/qcaret.svg` is the OS-facing icon (window, taskbar, Dock, `.exe`,
`.icns`); `qcaret-light.svg` is for in-app use with the light palette.
`tools/icons.sh` renders `qcaret.ico`, `QCaret.icns` and the PNGs in
`qcaret-ui-swt` from them; the build uses the committed outputs.

## Testing

| Class | Covers |
|---|---|
| `SmokeTest` (tag `swt`, at `integration-test`) | `java -jar qcaret.jar -v` prints the manifest version as its first line; `java -jar qcaret.jar` opens a 900 x 600 window at 50,40 and writes no `logs` folder; the jar's digests hold no CR and each `natives.` list is in name order. On linux-x86_64 the extracted bundle runs with `-l` and `PATH` holding only `uname` and `readlink`, reports its own `runtime/` as `java.home`, opens the same window, logs `First paint of file text` (the line `qcaret-bench` times), and after SIGTERM leaves one `qcaret-<pid>.log` holding the shutdown hook's line and no other file in `logs`. The bundle's `runtime/release` must list `jdk.charsets`, `jdk.localedata` and `java.management` |
| `CharsetProbe` | Run by the bundle's `java`; fails unless every encoding in `SmokeTest.ENCODINGS` is supported |

### CI bundle check

The CI `bundle` job runs each archive on its own runner through
`.github/scripts/check-bundle.sh` (Linux, macOS) or `check-bundle.ps1`
(Windows). For the target's bundle and then the universal zip:

| Check | Linux, macOS | Windows |
|---|---|---|
| Runtime | `os.arch`, `jdk.charsets`, `jdk.localedata`, `java.xml`, `java.management` | The same, plus the `javaw.exe` child's image and PE machine |
| Log read | `-d 1` on stderr | `-l`, `logs\qcaret.log`: `javaw` output is not captured |
| Started | `Main window open`, `Grammars read: 0 skipped`, no `Unhandled failure` or `UI task failed` | The same |
| Handoff | A second `bin/qcaret` or `QCaret` exits 0; the primary logs `Handoff of 1 files taken` | The same, the second launch through `javaw.exe`: `qcaret.exe` does not report the child's status (assumed from launch4j's `gui` header) |
| Native access | No `WARNING: A restricted` in either launch's output | None: no `java.lang.foreign` class loads |

A failed handoff opens its own window and does not exit, so each script bounds
the second launch at 60 s.
