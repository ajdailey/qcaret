# Tools and checksums

The Tools menu sits before Window. It holds one cascade per algorithm (MD5,
SHA-1, SHA-256, SHA-512), each with Generate, Generate from Files and Generate
from Selection into Clipboard, then Verify Checksums, Document Statistics
([Editor](editor.md)) and, off macOS, Preferences. No checksum command has a
default key. `ChecksumTools` dispatches the commands and answers
`CommandStates` for them; the digest, list and check code is in `core.io`.

| Command | Controller | Runs on |
|---|---|---|
| Generate... | `TextChecksum`: a field and its hash in UTF-8, updated per change; a result for an older text is dropped | `qcaret-cpu-` |
| Generate from Files... | `FileChecksums`: chosen files into `sha256sum` lines | `qcaret-io-` |
| Generate from Selection into Clipboard | `SelectionChecksum`: the selection's saved bytes, hex to the clipboard and a notice | `qcaret-cpu-` |
| Verify Checksums... | `ChecksumVerify`: each line of a list checked against its file | `qcaret-io-` |

MD5 and SHA-1 dialogs carry a note that the algorithm detects accidental
change and is no security check (`ChecksumAlgorithm.collisionResistant`).

## Algorithm availability

`ChecksumTools` probes each algorithm through `MessageDigest.getInstance` once
per process and logs a WARN for each one no provider offers, as MD5 under a
FIPS-only provider set. Its commands stay disabled, and `MainMenuBar` disables
the cascade with `CommandStates.unavailable` as its tool tip. A Verify line of
that algorithm reports unreadable. Not tested on a FIPS runtime.

## List format

`ChecksumList` reads and writes the `sha256sum` format:

- A line is `<hex> <marker><name>`; the marker is a space (text mode), or `*`
  (binary mode). The algorithm follows from the hex length: 32, 40, 64 or 128
  digits, either case.
- A line starting with `\` carries a name escaped as GNU coreutils writes it:
  `\\`, `\n`, `\r`. `ChecksumList.line` writes that form for a name holding a
  backslash or a line break.
- Blank lines are skipped. Any other line that does not parse, or is over 64 Ki
  chars, counts as bad; the first 1,000 bad line numbers are kept.

Generate from Files writes `<lowercase hex>  <name>` per file as it ends
(`FileChecksums.names`):

- Where every chosen file name is unique, the name is the file name.
- Where two share a name, every name is the path below the files' common
  folder, joined with `/` on every platform: `a/x.txt`, `b/x.txt`. Files on two
  Windows drives have no common folder and keep the root.
- The GNU escape applies only to a name holding a backslash or a line break,
  which leaves real backslashes in Linux and macOS names.

Save As starts in the common folder, so a list saved there verifies. It writes
UTF-8 with LF breaks; the default name is `MD5SUMS`, `SHA1SUMS`, `SHA256SUMS`
or `SHA512SUMS`. A file that cannot be read gets no line; the status line
counts it and names the first.

## Verify

The list is the selected document's text when the document has a file, unsaved
edits included, or a file chosen with Choose List, read as UTF-8 with malformed
bytes replaced. Names resolve against the list file's folder; an absolute name
stands as written. The dialog shows a line per entry in list order, then each
bad line, then the totals.

| Outcome | When |
|---|---|
| OK | The digest matches |
| FAILED | The digest differs |
| MISSING | No file at the name, a broken link included |
| unreadable | The file exists and the read failed (a folder, access denied), the name is not a valid path here, or the algorithm is unavailable |

A list written on Windows with backslash separators resolves on Linux and
macOS as one name holding backslashes, so its entries report MISSING.

## Selection bytes

Generate from Selection into Clipboard hashes the bytes Copy's text would have
in the saved file:

- Selections join in document order with the document's line ending, as Copy
  joins them; empty selections are skipped. A block selection's rows join the
  same way, empty rows included.
- Line breaks inside a selection hash as the document holds them.
- The text is encoded in the document's charset without a byte order mark. A
  character the charset cannot encode copies nothing; the notice says so.

The notice names the algorithm, the charset, the document's line ending and the
digest. The command is disabled without a selection.

## Runs, progress and cancel

`ChecksumRun` owns one dialog: its lines (the model Copy and Save As read), its
running run and Cancel. Choose starts a new run and cancels the running one;
closing the dialog cancels it. The worker reports through a `Feed`:

- Progress is the bytes read over the summed size of every file, taken before
  the first read, posted when the whole percent or the file changes.
- Lines go in batches of 64.
- A post from a canceled or replaced run is dropped.
- An unexpected exception ends the run with a status line and reaches the
  workers' failure handler.

`Checksums.ofFile` reads 64 KiB at a time and checks the cancel token before
each read, so Cancel stops mid-file.
