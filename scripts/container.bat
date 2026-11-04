@echo off
rem Runs the containerized build from cmd, PowerShell or a double-click.
rem compose.yaml holds the configuration; WSL uses scripts/container.sh.
rem
rem   container.bat                 the seven archives into dist\
rem   container.bat --out D:\rel    write the archives to D:\rel
rem   container.bat check           every test, SWT included, under Xvfb
rem   container.bat clean           mvnw clean, and empty dist\
rem   container.bat shell           a shell in the build image
rem   container.bat --rebuild       rebuild the build image first

setlocal enabledelayedexpansion
cd /d "%~dp0.."

set "IMAGE=qcaret-build:local"
set "REBUILD="
set "CMD="

rem A double-clicked window closes on exit and takes the output with it.
set "PAUSE_ON_EXIT="
echo %cmdcmdline% | find /i "%~nx0" >nul && set "PAUSE_ON_EXIT=1"

:parse
if "%~1"=="" goto ready
if /i "%~1"=="--rebuild" (set "REBUILD=1" & shift & goto parse)
if /i "%~1"=="--out" (set "QCARET_OUT=%~f2" & shift & shift & goto parse)
if /i "%~1"=="-h" goto usage
if /i "%~1"=="--help" goto usage

rem The first remaining argument names the command; the rest pass through.
rem An empty command runs compose.yaml's own.
set "TOK=%~1"
if /i "!TOK!"=="build" set "TOK="
if /i "!TOK!"=="check" set "TOK=xvfb-run -a ./mvnw -B clean verify"
rem dist is a mount source: empty it, never remove it.
if /i "!TOK!"=="clean" set TOK=sh -c "./mvnw -B clean && find dist -mindepth 1 -delete"
if /i "!TOK!"=="shell" set "TOK=bash"
set "CMD=!TOK!"
shift

:rest
if "%~1"=="" goto ready
set "CMD=!CMD! %~1"
shift
goto rest

:ready
where docker >nul 2>&1
if errorlevel 1 (
  echo Docker is required. Install Docker Desktop, or use scripts/container.sh in WSL.
  goto fail
)

rem A mount source must exist before the engine mounts it.
if not exist "dist" md "dist"
if defined QCARET_OUT if not exist "%QCARET_OUT%" md "%QCARET_OUT%"

if defined REBUILD goto build
docker image inspect %IMAGE% >nul 2>&1
if errorlevel 1 goto build
goto run

:build
echo ==^> Building %IMAGE%
docker compose build build
if errorlevel 1 goto fail

:run
if defined QCARET_OUT (echo ==^> Archives: %QCARET_OUT%) else (echo ==^> Archives: %CD%\dist)
docker compose run --rm build !CMD!
if errorlevel 1 goto fail
if defined PAUSE_ON_EXIT pause
endlocal
exit /b 0

:usage
echo usage: container.bat [--rebuild] [--out DIR] [build^|check^|clean^|shell^|command]
endlocal
exit /b 0

:fail
if defined PAUSE_ON_EXIT pause
endlocal
exit /b 1
