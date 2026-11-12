# Files and encodings

Paths, locks, atomic and safe writes and checksums (`core.io`), encodings, line
endings and bounded text reads (`core.text`), and utilities (`core.util`), of
[qcaret-core](../README.md).

## Paths

Every path from outside the process goes through `SafePaths`.

| Method | Rule |
|---|---|
| `userFile` | Rejects empty, NUL and, on Windows, device forms (`\\.\`, `\\?\`, `CON`, `NUL`, `COM1`...). Made absolute and normalized. An existing path resolves to its real path and must be a regular file: a FIFO blocks the reader and a device never ends. A missing path needs an existing parent directory |
| `settingsDirectory` | Absolute, normalized, created with POSIX `rwx------` where supported, resolved to its real path, must be a directory |
| `child` | A fixed name matching `[A-Za-z0-9._-]+`, resolved inside the directory |
| `holdLock`, `releaseLock` | Name and forget a lock file the process holds, any number at once, compared by real path and file key, so a hard link matches. `userFile` and `SafeSave.save` then reject it under any name: a save replaces the file, the lock stays on the old one, and the next process to try the lock takes the new one |

An editor opens whatever file its user names, so a user file path is
canonicalized and type-checked, not contained.

## Process lock

`ProcessLock.tryAcquire` takes an exclusive lock on a file, which the OS
releases at process death. It returns null when the lock is held, by another
process or this JVM. Reading the file or closing another channel on it leaves
the lock held.

| System | Call | Detail |
|---|---|---|
| Linux, macOS | `flock(2)` through `Flock` | `open` with `O_RDWR`, `O_CREAT`, `O_CLOEXEC`, mode 0600. `java.lang.foreign` by reflection, linked once per process; the API is a preview in Java 21 |
| Windows | `FileChannel.tryLock` | A lock belongs to its handle; no `java.lang.foreign` class loads |

The launchers pass `--enable-native-access=ALL-UNNAMED`. Plain `java -jar` on
Linux and macOS prints the JDK's three `WARNING:` lines on stderr; an
`Enable-Native-Access` manifest entry does not remove them on Java 21.

A JDK that lacks a name, has a different signature or denies native access
fails the link, and `tryAcquire` throws `IOException`. Do not fall back to
`FileChannel.tryLock` on Linux or macOS: it takes a POSIX record lock, which
any closing channel drops and which does not conflict with `flock` on local
Linux, so two launches could both hold the file. The bundles run Java 21; the
universal jar on JDK 22 or later has not been run, and a difference there takes
this path.

## Atomic writes

`AtomicFiles.write` puts the content in a temporary file in the target's
directory (POSIX `rw-------`), then moves it over the target with
`ATOMIC_MOVE`; on failure the target is unchanged and the temporary file
deleted. Nothing goes to `java.io.tmpdir`.

- `write(Path, InputStream)` streams the content through a 64 KiB buffer.
- `writeOwnerOnly`, for a secret, also replaces the temporary file's ACL with
  one entry for the current user where there are ACLs but no POSIX permissions
  (Windows): `USERDOMAIN\user.name`, then the bare name, then the file's owner
  when no name resolves. An elevated process's new file is owned by
  `BUILTIN\Administrators`, not the user.
- `writeOwnerOnly(Path, ContentWriter)` takes content too large for one array
  and forces it to disk before the move.
- On Windows a refused move is tried for up to 250 ms: a target open elsewhere
  without delete sharing refuses it for that moment.

## Safe save

`SafeSave.save(target, baseline, content, token)` saves a user file. It runs on
a worker.

1. A target that exists with a `FileStamp` (size, modified time, file key)
   other than `baseline` fails with `ExternalModificationException`; nothing is
   written. A missing target is written.
2. A symbolic link is resolved, so the link stays a link. A dangling link
   resolves to the missing file it names, relative to the link's directory.
3. A target that exists and fails `Files.isWritable` fails with
   `AccessDeniedException`; nothing is written. On Windows that is the DOS
   read-only attribute or an ACL.
4. The content goes to `.<name>.<random>.qcaret-tmp` in the target's directory
   and is forced to disk, then the file moves over the target with
   `ATOMIC_MOVE`. Replacing a file, the temporary file is created owner-only,
   takes the target's group before the write, and takes the target's POSIX
   permissions or DOS hidden and system flags before the move. A new file takes
   the default mode. `SafeSave` checks the token on each write to the temporary
   file, so a `ContentWriter` need not; a cancel before the move leaves the
   target unchanged and deletes the temporary file.
5. The target is written in place (truncate, write, force) when it has more
   than one hard link, its owner differs from the temporary file's, the user
   cannot give the temporary file its group, its directory is not writable, or
   the move fails five times 100 ms apart (Windows sharing violations). The
   token is checked before the truncate and never after it: an in-place write
   runs to its end, so Esc does not stop one on a large file. A failure partway
   leaves the target partly written.

## Binary detection

`BinaryDetector` calls a file binary when its first 64 KiB hold a NUL byte
(unless the encoding is UTF-16 or UTF-32) or more than 10% C0 controls other
than tab, LF, FF and CR.

## Checksums

`ChecksumAlgorithm` names MD5, SHA-1, SHA-256 and SHA-512 with their hex
lengths. `Checksums.ofFile` digests a file 64 KiB at a time, checking the
cancel token before each read; `Checksums.TextDigester` encodes text pieces
into a digest and fails on a char the charset cannot encode. `ChecksumList`
parses and writes `sha256sum` lines, and `ChecksumCheck` checks one line
against its file. The Tools menu built on them is in
[qcaret-app Tools](../../qcaret-app/docs/tools.md).

## Encodings

`TextEncoding` is a charset plus whether the file has its byte order mark; the
BOM is not text, and a save writes it back. BOM-sensing `UTF-16` and `UTF-32`
are taken as their big-endian forms. `TextEncoding.forName` returns null for a
name this Java lacks, so a remembered encoding the runtime does not have reads
as none. `TextEncoding.MENU` lists the Encoding menu's charsets; the
distribution smoke test checks that every bundle runtime has them.

`EncodingDetector.detect` examines the whole file, in this order; `candidates`
lists the same choices from the file's first 64 KiB alone, for a caller that
tries each over the file (`DocumentFiles.load`):

1. BOM: UTF-8, UTF-32 LE/BE, UTF-16 LE/BE. `FF FE 00 00` is UTF-32LE, so a
   UTF-16LE file whose first char is U+0000 reads as UTF-32LE.
2. UTF-16 without BOM: at least 30% zero bytes at one parity and under 5% at
   the other in the first 64 KiB.
3. Declared: an XML declaration, an HTML `meta charset` or a coding line
   (`coding:` or `coding=` in the first two lines, within the first 1 KiB),
   else a modeline's charset when the caller passes the file's tail (see
   [Modelines](#modelines)); used only when the whole file decodes strictly
   with it. A name Java lacks is retried without hyphens (`latin-1`); a declared
   UTF-16 or UTF-32 is ignored.
4. Configured: the caller's EditorConfig charset (`LoadOptions.configured`),
   under the same strict-decode condition.
5. The whole file is valid UTF-8 (an empty file is).
6. The fallback (`encoding.fallback`, windows-1252 by default), or ISO-8859-1
   when the fallback does not decode the file strictly.

A BOM or the UTF-16 pattern leaves no other candidate, so a BOM wins over a
declared or configured charset.

`forced` applies a user's charset, keeping a matching BOM as the file's BOM.

## Per-file settings

`FileSettings.of` combines a file's EditorConfig properties and its modelines,
a modeline's value replacing EditorConfig's: width, spaces or tabs, line ending,
charset, `trim_trailing_whitespace` and `insert_final_newline` (the last two
from EditorConfig only), plus the contributing `.editorconfig` paths and the
modeline kind. The app applies them ([Per-file
settings](../../qcaret-app/docs/documents.md#per-file-settings)).

| Property | Width and spaces |
|---|---|
| `indent_style = space` | Spaces; width `indent_size`, else `tab_width` |
| `indent_style = tab` | Tabs; width `tab_width`, else `indent_size` |
| No `indent_style` | Width `tab_width`, else `indent_size` |

A width outside 1 to 16 is ignored. `charset` is `latin1`, `utf-8`,
`utf-8-bom`, `utf-16be` or `utf-16le`; `declaredCharset` leaves out UTF-16,
which detection finds by its BOM or zero bytes, so a UTF-16 value sets only a
new file's encoding (with a BOM).

### EditorConfig

`EditorConfig.resolve` (`@WorkerThread`) reads `.editorconfig` in the file's
directory and each parent, up to one with `root = true` or the file system root,
and applies the matching sections farthest file first, in file order; `unset`
removes a property. Then the EditorConfig core libraries' defaults apply
(`indent_size = tab` for tab style, `tab_width` from a numeric `indent_size`).

| Case | Result |
|---|---|
| Path | The caller passes the real path; the walk uses its parents as named |
| `.editorconfig` a symbolic link | Followed |
| `.editorconfig` a directory, FIFO or device | Skipped unread |
| Over 256 KiB or 10,000 lines, not UTF-8, unreadable, a line that is not blank, a comment, a section or `key = value` | Contributes nothing, its `root` included; named in `Result.problems` |

`EditorConfigFile.parse` lower-cases keys and the standard properties' values; a
`#` or `;` inside a value belongs to it. `EditorConfigGlob` compiles a section
name to a regular expression over the path below the file's directory (glob
table in its Javadoc): a name without `/` matches at any depth, one with `/` is
anchored.

### Modelines

`Modelines.parse` reads vim modelines in the first and last 5 lines and Emacs
file variables (the `-*- -*-` line, on line 2 after a `#!` line, and a
`Local Variables:` block ending in `End:` in the last 3,000 chars); its
Javadoc lists the options. vim's values replace Emacs's, and only a recognized
option makes a modeline count. `Modelines.codingLine` is the coding line rule
of [detection](#encodings); `TextModelines.read` (`core.document`) parses a
document's text from its two ends, at most 64 KiB of the first lines.

## Line endings

`LineEndingStats` counts LF, CR LF and CR breaks and reports the dominant and
mixed state; `LineEndingStats.Counter` counts a text read in pieces, a CR at a
piece's end waiting for the next char. `LineEnding` names the three kinds.

## Bounded reads

`BoundedChars` is a `CharSequence` over a line that throws `LimitReached` once
`charAt` passes a read count, and checks a `CancellationToken` every 4,096
reads, so a backtracking regex over it ends. `lineLimit` is `8 * length +
65,536`.

## Log text

`LogText.oneLine` reduces a value that did not originate in QCaret's code (a
path, a pattern, an exception message) to one line before it reaches a logger:
C0 controls, DEL, NEL, U+2028 and U+2029 become `_`, and the result is cut at
1,024 chars. `config/findsecbugs-taint.txt` registers it with find-sec-bugs as
removing CR and LF.

## Utilities

| Class | Contract |
|---|---|
| `Checks` | `atLeast` and `notNegative` throw `IllegalArgumentException` naming the argument |
| `Streams` | `readLimited` reads a stream to its end and throws `IOException` once it passes the limit, so an oversized file is refused rather than truncated |

### Operating system

`OsTraits` answers each question whose answer differs by operating system, one
class per system. `OsTraits.current()` is chosen once from `os.name`: a name
starting `Windows` or `Mac`, ignoring case, picks that system, and any other
name picks Linux. Only `core.util.os` reads `os.name` in core, app and the
launcher (`ArchitectureTest.osNameOnlyInTheOsSeam`); tests take another
system's answers from `OsTraits.of(name)`.

| Method | Caller | Windows | macOS | Linux |
|---|---|---|---|---|
| `fileNamesIgnoreCase` | `NameFilter.PLATFORM_IGNORES_CASE` | true | true | false |
| `fileLock` | `ProcessLock`, `Flock.Constants` | `CHANNEL` | `FLOCK_MACOS` | `FLOCK_LINUX` |
| `opensDeviceNames` | `SafePaths` device-form check | true | false | false |
| `userSettings` | `SettingsLocation` per-user mode | `%APPDATA%\QCaret` | `~/Library/Application Support/QCaret` | `$XDG_CONFIG_HOME/qcaret` |
| `confinesToSettings` | `SettingsLocation.confined` | false | false | true |
| `webView2` | `PlatformSetup` | true | false | false |
| `fontDirectories` | `FontFiles.installedFamilies` | `%WINDIR%\Fonts`, `%LOCALAPPDATA%\Microsoft\Windows\Fonts` | `/System/Library/Fonts`, `/Library/Fonts`, `~/Library/Fonts` | `~/.fonts`, the XDG data `fonts` folders |

`OsTraits.description()` is the About dialog's OS line. `EnvPaths.absolute`
accepts a variable's value only as a non-empty absolute path.
