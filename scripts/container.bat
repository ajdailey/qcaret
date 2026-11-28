@echo off
rem Runs the containerized build from cmd, PowerShell or a double-click.
rem compose.yaml holds the configuration; WSL uses scripts/container.sh.
rem
rem   container.bat                 archives, installers, packages, SBOM into dist\
rem   container.bat --out D:\rel    write the archives to D:\rel
rem   container.bat check           every test, SWT included, under Xvfb
rem   container.bat clean           mvnw clean, and empty dist\
rem   container.bat shell           a shell in the build image
rem   container.bat --rebuild       rebuild the build image first
rem   container.bat ./mvnw -B ...   any other command, verbatim in the image
rem
rem QCARET_CONTAINER_DRYRUN=1 prints the output directory and the docker compose
rem run line, and runs nothing.

setlocal DisableDelayedExpansion
cd /d "%~dp0.."

set "IMAGE=qcaret-build:local"
set "REBUILD="
set "OUT="

rem Numbered arguments split at commas, semicolons and =, so the script reads
rem the argument line whole: echo shows a REM line without acting on & | < > ^,
rem and the line is read back from the echo. The FOR replaces a percent sign
rem followed by Q in an argument.
set "argsfile=%TEMP%\qcaret-container-%RANDOM%%RANDOM%.args"
set "prompt=#"
(
  @echo on
  @for %%Q in (1) do rem #%*#
  @echo off
) > "%argsfile%"
set "argline="
for /f "usebackq delims=" %%l in ("%argsfile%") do set "argline=%%l"
del "%argsfile%" 2>nul
if not defined argline (
  echo container.bat could not read its arguments through "%argsfile%".
  exit /b 1
)

setlocal EnableDelayedExpansion
set "REST=!argline:*rem #=!"
set "REST=!REST:~0,-2!"
set "QT=""

rem A double-clicked window closes on exit and takes the output with it.
set "PAUSE_ON_EXIT="
set "LINE=!cmdcmdline!"
if /i not "!LINE:%~nx0=!"=="!LINE!" set "PAUSE_ON_EXIT=1"

:parse
call :split
if not defined TOK goto ready
if /i "!TOK!"=="--rebuild" (set "REBUILD=1" & goto parse)
if /i "!TOK!"=="-h" goto usage
if /i "!TOK!"=="--help" goto usage
if /i "!TOK!"=="--out" (
  call :split
  set "OUT=!TOK!"
  goto out
)
if /i "!TOK:~0,6!"=="--out=" (
  set "OUT=!TOK:~6!"
  goto out
)

rem The first other token names the command, and the line passes on from there
rem as typed. A named command takes no arguments; an empty one runs
rem compose.yaml's own.
set "CMD=!TOK!!REST!"
if /i "!TOK!"=="build" set "CMD="
if /i "!TOK!"=="check" set "CMD=xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -B clean verify"
rem dist is a mount source: empty it, never remove it.
if /i "!TOK!"=="clean" set CMD=sh -c "./mvnw -B clean && find dist -mindepth 1 -delete"
if /i "!TOK!"=="shell" set "CMD=bash"
goto ready

:out
if defined OUT set "OUT=!OUT:"=!"
if not defined OUT (
  echo --out needs a directory
  goto fail
)
for %%P in ("!OUT!") do set "QCARET_OUT=%%~fP"
goto parse

:ready
if "!QCARET_CONTAINER_DRYRUN!"=="1" goto dryrun

where docker >nul 2>&1
if errorlevel 1 (
  echo Docker is required. Install Docker Desktop, or use scripts/container.sh in WSL.
  goto fail
)

rem A mount source must exist before the engine mounts it.
if not exist "dist" md "dist"
if defined QCARET_OUT if not exist "!QCARET_OUT!" md "!QCARET_OUT!"

if defined REBUILD goto build
docker image inspect %IMAGE% >nul 2>&1
if errorlevel 1 goto build
goto run

:build
echo ==^> Building %IMAGE%
docker compose build build
if errorlevel 1 goto fail

:run
if defined QCARET_OUT (echo ==^> Archives: !QCARET_OUT!) else (echo ==^> Archives: !CD!\dist)
docker compose run --rm build !CMD!
if errorlevel 1 goto fail
if defined PAUSE_ON_EXIT pause
endlocal
exit /b 0

:dryrun
if not defined QCARET_OUT set "QCARET_OUT=!CD!\dist"
echo(QCARET_OUT=!QCARET_OUT!
echo(docker compose run --rm build !CMD!
endlocal
exit /b 0

:split
rem Moves the first space-delimited token of REST, quotes kept, into TOK.
set "TOK="
set "INQ="
:split_blank
if not defined REST exit /b 0
if "!REST:~0,1!"==" " (
  set "REST=!REST:~1!"
  goto split_blank
)
:split_char
if not defined REST exit /b 0
set "C=!REST:~0,1!"
if not defined INQ if "!C!"==" " exit /b 0
if "!C!"=="!QT!" (if defined INQ (set "INQ=") else set "INQ=1")
set "TOK=!TOK!!C!"
set "REST=!REST:~1!"
goto split_char

:usage
echo usage: container.bat [--rebuild] [--out DIR] [build^|check^|clean^|shell^|command]
echo QCARET_CONTAINER_DRYRUN=1 prints the docker compose run line and runs nothing.
endlocal
exit /b 0

:fail
if defined PAUSE_ON_EXIT pause
endlocal
exit /b 1
