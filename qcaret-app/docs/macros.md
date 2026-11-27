# Macros

A macro is a list of steps recorded from the operations the controllers run,
not from keys or widget events. `app.macro` holds the step model (`MacroStep`,
`Macro`), the text format (`MacroFormat`), the folder (`MacroStore`), name rules
(`MacroNames`), command classes (`Recordability`) and the recorder
(`MacroRecorder`). `MacroHooks`, one per `WindowSet`, owns the recording and
the runs and takes the controllers' hook calls, an editor's through its
`EditorMacros`; `MacroPlayer` runs a macro and `MacroFind` runs its find steps.
Record Macro (a check command) and Play Recorded Macro are `CommandGroup.MACROS`
commands with no menu entry or default key.

## Recording

One recording runs at a time in the process. It belongs to the window that
started it and takes the steps of that window's selected editor; Record Macro
in another window stops it first. Stopping keeps a recording with a step as the
recorded macro, held in `MacroHooks` until a new recording replaces it or the
process ends.

| Hook | Called from | Records |
|---|---|---|
| `commandStarting` | `ApplicationController.execute`: menu, toolbar, palette, window keys | the command, by its class |
| `editorCommand` | `EditorController.execute` for a key the editor runs itself; skipped inside `execute` above | the command, by its class |
| `typed` | `EditorController.type` | `type`; consecutive typed text merges into one step |
| `viewReplaced` | `replaceFromView`, `commitComposition` | `insert` for an input method's commit; a mouse drop or paste is left out with a notice |
| `completed` | `CompletionInput` accepting a word | `complete` |
| `found`, `replaced` | `FindController`: Find Next and Previous, typing in the bar, Replace, Replace All | `find`, `replace`, `replace-all`; consecutive `incremental` finds collapse to the last |
| `lineChosen`, `tabWidthChosen`, `dateTimeChosen`, `sequenceChosen` | the Go to Line, Tab Width, Insert Date and Time and Insert Number Sequence answers | `go-to-line`, `tab-width`, `date-time`, `number-sequence` |
| `mouse` | the editor's mouse selection, block, caret and fold calls | nothing; a notice when the selection changes |
| `notRecorded` | `ApplicationController.execute` when the focused hex pane takes the command | nothing; a notice |

A command is recorded only when `CommandStates` enables it at that moment, so
a key that ran as a no-op (Undo with nothing to undo) leaves no step that would
fail on playback. `Recordability.of` classifies every `CommandId` in a switch
with no `default`:

| Class | Commands | Recording |
|---|---|---|
| `COMMAND` | caret, selection and block moves, multi-caret, line, case, comment, indent, sort, codec, format, fold and bookmark commands, Cut, Copy, Paste, Delete, Newline, Tab, Undo, Redo, Undo and Redo Selection, line-ending conversions, tab widths 2, 4 and 8, Indent with Spaces, Column Mode, Esc | a `command` step |
| `OWN_STEP` | Find, Replace, Find Next, Find Previous, Go to Line, Tab Width > Other, Insert Date and Time, Insert Number Sequence, Complete Word | the step its result produces |
| `NOTICE` | file, print, export, compare, HyperSearch and results, checksum, tab and window switching, Read Only, Preferences, Play Recorded Macro | nothing; the status bar names the command |
| `SILENT` | view, theme, zoom, split, Markdown mode, palette and help commands, Record Macro | nothing |

An `incremental` find replays forward from the primary selection's start, where
the bar searches from the position the selection started at when the bar
opened or the last match was found. A caret moved after the bar opened makes
the two differ.

## Playback

`MacroPlayer` runs one macro against the window's selected editor. It refuses
a macro without steps, a read-only document, and a window that records or runs
a macro.

- A macro without Undo or Redo steps runs as one undo step: a `Document` group
  opens before the first step and closes on every exit path, the window
  closing included. A macro with them runs without the group, since Undo cannot
  run inside an open one.
- While a run is active, `EditorController` drops `type`, `execute`,
  `replaceFromView`, `preeditFromView`, the mouse selection calls and the
  completion choice for every view of the document, and the find bar's search,
  Replace and Replace All do nothing on it. `ApplicationController.execute`
  runs only `SILENT` commands. Esc (`CANCEL`) in a view of the document stops
  the run and cancels its worker commands. The player's own steps pass the
  hold. The completion list stays closed.
- Steps run in UI turns of at most `MacroPlayer.TURN_NANOS` (8 ms), each turn
  posted through the `UiDispatcher`; one step longer than that is not split.
  After a command step the run resumes through `BusyCounter.whenIdle` on the
  editor's busy cursor, which counts every worker command from command to
  apply, so a sort or codec result applies before the next step. A find step
  resumes from `MacroFind`'s outcome.
- A step fails when its command is disabled, a find or Replace finds no match
  (playback never wraps), a worker command ends with an error notice
  (`EditorMacros.failed`: a stale result, a codec refusal, a format or path
  error), Insert Number Sequence overflows, or the run's tab is closed or no
  longer selected. The run stops there with the completed steps applied, and
  the notice names them: `Stopped at iteration 12 of 50: step 3, Find found no
  match`.
- A counted run ends after its count. A run to the end (count 0) stops when,
  after an iteration, the distance from the primary selection's end to the
  document end is zero or did not decrease; a find with no match ends it as
  done. A completed run shows `Macro ran 3 times`.

Replaying a recording on a copy of the starting text, selections, clipboard
and settings reproduces the text and selections. The settings that steer the
result are auto-close, the grammar's indent rules, tab width, Indent with
Spaces, fold state and wrap width; Paste reads the clipboard and Insert Date
and Time the clock at replay.

## Macro files

`MacroStore` keeps one `<name>.qcmacro` file per saved macro in `macros/` under
the settings folder, so portable and `--settings` starts keep them there. It
lists, writes, renames and deletes on a `qcaret-io-` worker and reports on the
UI thread; a write goes to a temporary file that replaces the target. Nothing
watches the folder.

A name is 1 to 64 letters, digits, spaces, `-` and `_`, with no outer space,
not a Windows device name (`CON`, `NUL`, `COM1`), and unique ignoring case. A
file over 1 MiB, not UTF-8, not `MacroFormat` text, not a valid name, or a
second name equal ignoring case lists as unreadable with its reason, with one
WARN per file name.

The text is UTF-8, LF or CRLF. The first line is `qcaret-macro 1`; `#` lines
and blank lines are skipped; an optional `key Mod+Shift+1` line gives the
macro's key. Strings take JSON string escapes, so control chars, CR, LF and
surrogate pairs round-trip; a string with a lone surrogate is not written. One
line that does not parse refuses the file.

| Line | Step |
|---|---|
| `command CARET_DOWN` | a `COMMAND` class command, by enum name |
| `type "text"` | typed text, replayed one code point per call so auto-close and smart indent act as when keyed |
| `insert "text"` | an input method's commit, replayed as one typed call |
| `complete "word"` | the word replaces the identifier prefix before each caret |
| `find "text" forward case=on word=off regex=off` | `forward`, `backward` or `incremental` |
| `replace "find" "with" case=off word=off regex=off` | Replace; `replace-all` for Replace All |
| `go-to-line 120`, `go-to-line 120:5` | line, and column when not 1 |
| `tab-width 6` | 1 to 16 |
| `date-time "yyyy-MM-dd"` | a `DateTimeFormatter` pattern |
| `number-sequence start=1 step=1 pad=3 base=decimal` | `decimal`, `hexUpper` or `hexLower` |
