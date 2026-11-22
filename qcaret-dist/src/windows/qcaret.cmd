@rem Starts QCaret with the bundled runtime, else JAVA_HOME, else javaw.exe on
@rem PATH, and returns at once. Every line starts with @, so a calling batch
@rem keeps its echo state. The arguments reach Java as text in QCARET_CMDLINE,
@rem which Java reads in UTF-16: cmd.exe never parses them a second time, and
@rem neither the ANSI code page nor the Java launcher's wildcards apply.
@setlocal DisableDelayedExpansion
@set "self=%~f0"
@set "jar=%~dp0lib\qcaret.jar"
@set "quit=exit /b"
@set "javaw=%~dp0runtime\bin\javaw.exe"
@if exist "%javaw%" goto mode
@set "javaw=%JAVA_HOME%\bin\javaw.exe"
@if defined JAVA_HOME if exist "%javaw%" goto mode
@set "javaw="
@for %%j in (javaw.exe) do @set "javaw=%%~$PATH:j"

:mode
@setlocal EnableDelayedExpansion
@rem Explorer, a shortcut or another program starts a .cmd as
@rem cmd.exe /c ""<this file>" <arguments>". That cmd.exe has already split the
@rem line at an unquoted &, so the arguments come from the raw line instead,
@rem and the script ends that cmd.exe with exit so the rest never runs.
@set "line=!cmdcmdline!"
@set "args=!line:*/c ""=!"
@set "rest=!args:*%~nx0"=!"
@set "expect=!self!"!rest!"
@if /i not "!expect!"=="!args!" goto prompt
@set "quit=exit"
@rem Drops the closing quote of the /c "..." pair.
@set "last=!rest:~-1!"
@set "last=!last:"=!"
@if not defined last set "rest=!rest:~0,-1!"
@goto run

:prompt
@rem A command prompt or a batch file: %* holds the arguments after that
@rem cmd.exe parsed them. echo shows a REM line without acting on & | < > ^,
@rem so %* is read back from the echo. The first REM shows whether the caller
@rem had echo on, to restore it after.
@endlocal
@set "argsfile=%TEMP%\qcaret-%RANDOM%%RANDOM%.args"
@set "prompt=#"
@(
  for %%a in (1) do rem
) > "%argsfile%"
@for %%f in ("%argsfile%") do @if %%~zf gtr 0 set "echoed=1"
@(
  @echo on
  @for %%a in (1) do rem #%*#
  @echo off
) > "%argsfile%"
@if defined echoed echo on
@set "argline="
@for /f "usebackq delims=" %%l in ("%argsfile%") do @set "argline=%%l"
@del "%argsfile%" 2>nul
@if not defined argline goto unread
@setlocal EnableDelayedExpansion
@set "rest=!argline:*rem #=!"
@set "rest=!rest:~0,-2!"

:run
@if not defined javaw goto nojava
@set "QCARET_CMDLINE=!rest!"
@rem /b gives Java this console's output, so -v, -h and errors print there
@rem when it is redirected or piped. QCaret closes it once its window opens.
@start "" /b "!javaw!" -XX:MaxRAMPercentage=50 -Dqcaret.launcher=cmd -jar "!jar!"
@%quit% 0

:nojava
@>&2 echo QCaret needs Java 21: no runtime folder beside qcaret.cmd, no javaw.exe under JAVA_HOME or on PATH.
@rem Keeps a double-clicked console open on the message.
@pause
@%quit% 1

:unread
@>&2 echo qcaret.cmd could not read its arguments through "%argsfile%".
@exit /b 1
