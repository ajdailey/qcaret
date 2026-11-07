# Search, replace and workspace walks

The matcher and text search, the file walk, file replace and backups
(`core.search`), the line diff (`core.diff`) and the workspace listings and
index (`core.workspace`), of [qcaret-core](../README.md). The find bar,
HyperSearch and replace controllers are in [qcaret-app
search](../../qcaret-app/docs/search.md).

## Matcher

`SearchQuery` (text, regex, case, whole word) compiles to a `TextMatcher`; an
invalid regex throws `PatternSyntaxException` and empty text
`IllegalArgumentException`.

- A regex compiles with `MULTILINE`, so `^` and `$` match at CR, LF and CR LF.
- Case folding is Unicode-aware; whole word (`SearchQuery.wholeWord`) rejects a
  letter, digit or `_` touching either end. A regex that ends inside `\Q`, a
  `(?x)` comment, or a `\Q` inside such a comment (`Pattern` reads it as a
  quote) gets `\E`, a line feed or both before the wrapper's closing
  parenthesis.
- Lookbehind and lookahead see text outside a searched range.
- `next`, `previous` and `all` return non-empty `Match`es; empty matches are
  skipped. `replacementAt` returns the replacement when the query matches
  exactly a range; `replaceAll` returns one `Replacement` per match.
- Every call checks the `CancellationToken` every 4,096 chars it reads, so a
  catastrophic regex stops on cancel. A `StackOverflowError` in the regex
  engine becomes `PatternTooComplexException`.
- A case-sensitive query whose matches all start with a literal of 3 or more
  chars runs the regex only where that literal occurs, anchored there
  (`lookingAt` on a region with transparent bounds), so the matches are those
  of a `find` from the search's start. The literal is a literal query's text,
  or a regex's `SearchQuery.requiredPrefix`: the chars before the first
  metacharacter (`\ ^ $ . | ? * + ( ) [ ] { }`), less the last code point when
  `?`, `*` or `{` follows it, and none when the pattern has a `|`. A regex
  with `\G` (it matches where the search starts) and a literal starting with a
  low surrogate (`Pattern` starts no match inside a surrogate pair) run
  without it.
- The literal search uses `String.indexOf` in 4,096-char windows on a `String`,
  also behind `TextSearch`'s view of a stretch, and reads other text char by
  char.
- `ReplaceTemplate` parses a regex replacement once: `$n`, `${name}`, `\n`,
  `\t`, and a backslash before any other char inserts that char. A missing
  group or a dangling `\` or `$` throws `IllegalArgumentException`. A literal
  query's replacement is inserted as is.

The calls are `@WorkerThread`: the find bar runs them on `qcaret-cpu-` workers
from a snapshot, and HyperSearch reuses them.

## Text search

`TextSearch.search` lists every match of a `TextMatcher` in one text (a
snapshot or a decoded file) as `Hit`s: offsets, line, column, the hash of the
matched text and a snippet of the first line, control chars as spaces, trimmed,
and cut to 500 chars around the match on a longer line. Optional ranges limit
it to the selections; a match then lies inside one.

- A line over `maxLineChars` is not searched. One chunked pass finds those
  lines; each searched stretch ends before a skipped line, so no match runs
  into one. `skippedLines` counts each such line once when it or its break lies
  in a searched range: starting inside a range, or before it and running into
  it. A line starting at a range's end or after does not count, although the
  pass reads up to one 64 Ki-char chunk past the end.
- The line-start scan and the long-line pass find CR and LF with
  `String.indexOf` in 4,096-char windows on a `String`, also behind a `Prefix`,
  and read other text char by char in 64 Ki-char chunks (`SCAN_CHARS`). Each
  window or chunk checks the `CancellationToken` once.
- A `PatternTooComplexException` ends the search: the stretch is searched again
  line by line, each line read through a view that ends at the line, and
  `tooComplexLine` names the line that overflowed. The hits before it are kept.
- `limit` bounds the hits; `limited` reports that more existed.

## File search

`FileSearch.run` walks a directory on the calling `qcaret-search-` worker
without following symbolic links. `FileFilter` holds the masks: `;`-separated
globs on the file name (empty include takes every file), excluded directory
names (default `.git;.svn;.hg;target;node_modules;build`) and hidden files (a
leading `.` or the platform's hidden attribute). Paths in `searchedElsewhere`
(open documents) are not read. Each file is read and decoded on a `qcaret-io-`
thread, at most four at a time, and matched on the CPU pool.

| File | Result |
|---|---|
| Over `maxFileBytes` | `Skip.TOO_LARGE` |
| `BinaryDetector` says binary | `Skip.BINARY` |
| Read fails, or its directory cannot be listed | `Skip.UNREADABLE` |
| `MemoryBudget` refuses five bytes per file byte for the read | `Skip.NO_MEMORY` |
| Otherwise | Decoded with `EncodingDetector` (malformed input as U+FFFD), matched; `FileHits` with encoding, `FileStamp` and SHA-256 of the bytes read when it has hits, skipped lines or a regex failure |

The SHA-256 is computed on the CPU pool after the match, and only for a file
`FileHits` reports; the file's bytes are held until the match ends, within the
five-byte reservation. The `Sink` receives results on worker threads in no
fixed order; its `room()` bounds the hits, and 0 ends the walk.

Cancel stops the walk; `run` returns once the reads in flight end. The
`CancellationToken` is checked before each 64 KiB block (`BLOCK_BYTES`) of the
file read, each strict decode that picks the encoding, the decode and the
SHA-256; the binary check reads only the head. A file canceled mid-read counts
as done with no skip, and its reservation is released.

`FileSearch.searchFile` reads, decodes and matches one file on the calling
thread, as the walk does each file, for a per-file rerun. Its `FileHits` has a
null digest when the outcome has no hits, skipped lines or regex failure.

## File replace

`FileReplace.replace` replaces a directory replace's matches in one file on
disk; any skip leaves the file untouched. Six bytes per file byte are reserved
from the `MemoryBudget` while a file is replaced.

1. Read the stamp, then the bytes. Skip a missing, unreadable or read-only
   (`Files.isWritable`) file, one over the size limit, one whose SHA-256
   differs from the bytes searched, a binary file, one that does not decode
   with `REPORT` in the encoding searched with, and one whose text does not
   encode back to the same bytes (a stateful encoding would rewrite unchanged
   text).
2. Match each checked span again on exactly its range
   (`TextMatcher.replacementAt`); a span that does not match is dropped. Line
   breaks in a replacement become the file's dominant line ending; the other
   breaks stay as read.
3. Encode with `REPORT`; an unmappable char skips the file. The BOM is kept.
4. `ReplaceBackups.record` writes the original bytes, then `SafeSave` writes
   the file with the stamp from step 1 as its baseline. A change between the
   read and that stamp check skips the file; a change after the check is
   overwritten (the write time of one file).

### Backups

`ReplaceBackups` keeps one directory per run under
`<settings>/replace-backups/`, named by its UTC start time. Each replaced file
gets `<n>.bak`: a header line with its path and the SHA-256 of its bytes before
and after, then the original bytes, forced to disk before the file is replaced.
A write that does not happen deletes it.

- After a failed write, `writeFailed` deletes the backup only when the file
  still holds its original bytes. Otherwise the backup stays, and the digest of
  the bytes the write left replaces the header's after digest.
- `restore` writes a backup back through `SafeSave` only while the file holds
  the bytes the run left; a file changed since is refused, and one already
  holding its original reports unchanged. A restore that fails partway records
  the bytes it left the same way, so a retry works.
- Its digests and the copy stream 1 MiB blocks through the backup's body. The
  digest of the body is checked before the write, since `SafeSave` can write in
  place.
- `prune` deletes runs older than the days kept.

## Line diff

`LineDiff.diff` returns the shortest edit script between two line lists (Myers'
greedy algorithm after matching the common prefix and suffix), each run of
changes listing its removed lines before its added ones. Past 1,000 edits the
rest is reported as all lines removed, then all added: correct, not shortest,
and bounded in time and memory. `hunks` groups a script into changes with a
given number of context lines.

## Workspace folder

`WalkRules` leaves out hidden entries (`HiddenFiles`: a name starting with `.`,
or the DOS hidden attribute on Windows) and directories with an excluded name,
with their trees. `FileSearch` applies the same hidden rule.

`DirectoryListing.read` lists one directory for the browser: directories first,
then files, each by name ignoring case. It reads at most 50,000 entries, in
directory order, and reports the cut. A link to a directory lists as a
directory unless its target contains the link (`CYCLE`, never listed further);
a link to a file and a broken link list as files.

`FileIndex.build` walks a folder for Quick Open into paths relative to the
root, joined with `/`. It follows no link to a directory, so a link cycle is
never entered, and lists links to files. Each path reserves 64 bytes plus 2 per
char from the `MemoryBudget`; the walk stops at its file limit (`MAX_FILES`,
200,000) or at a refused reservation and reports which (`Cut`). A cancel throws
`CancellationException` with nothing left charged; the owner returns
`charged()` when it drops the index. An unreadable directory is skipped.
