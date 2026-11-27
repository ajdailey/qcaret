@echo off
rem Adds QCaret to the current user's Open With lists and Explorer right-click
rem menu, or removes it with /u. Writes only under HKCU; the default program
rem for a type stays the user's.
setlocal DisableDelayedExpansion
set "exe=%~dp0qcaret.exe"
rem !exe! expands after cmd splits the line, so & ( ) ^ in the path stay literal.
setlocal EnableDelayedExpansion
rem The long root name, as reg query prints it, for :deleteifempty.
set "classes=HKEY_CURRENT_USER\Software\Classes"
set "verb=%classes%\*\shell\QCaret.Edit"
rem No .bat or .cmd: with QCaret.File in their OpenWithProgids, ShellExecute fails to run them.
set "extensions=.txt .md .log .csv .xml .json .yml .yaml .ini .conf .cfg .properties .c .h .cpp .hpp .cs .java .py .rb .js .ts .css .html .sh .ps1 .sql"

if /i "%~1"=="/u" goto remove
if not "%~1"=="" goto usage
if not exist "!exe!" goto missing

reg add "%classes%\QCaret.File" /ve /d "Text file (QCaret)" /f >nul || goto failed
reg add "%classes%\QCaret.File\DefaultIcon" /ve /d "\"!exe!\",0" /f >nul || goto failed
reg add "%classes%\QCaret.File\shell\open\command" /ve /d "\"!exe!\" \"%%1\"" /f >nul || goto failed
reg add "%classes%\Applications\qcaret.exe\shell\open\command" /ve /d "\"!exe!\" \"%%1\"" /f >nul || goto failed
for %%e in (%extensions%) do (
  reg add "%classes%\%%e\OpenWithProgids" /v "QCaret.File" /t REG_NONE /f >nul || goto failed
)
rem A verb under * is no type's default, so each type keeps its own open.
reg add "%verb%" /v MUIVerb /d "Edit in QCaret" /f >nul || goto failed
reg add "%verb%" /v Icon /d "\"!exe!\",0" /f >nul || goto failed
reg add "%verb%\command" /ve /d "\"!exe!\" \"%%1\"" /f >nul || goto failed
echo QCaret is in the Open With list for %extensions% and in the Explorer right-click menu.
exit /b 0

:remove
for %%e in (%extensions%) do (
  reg delete "%classes%\%%e\OpenWithProgids" /v "QCaret.File" /f >nul 2>nul
)
reg delete "%classes%\QCaret.File" /f >nul 2>nul
reg delete "%classes%\Applications\qcaret.exe" /f >nul 2>nul
reg delete "%verb%" /f >nul 2>nul
call :deleteifempty "%classes%\*\shell"
call :deleteifempty "%classes%\*"
echo QCaret is out of the Open With lists and the Explorer right-click menu.
exit /b 0

:deleteifempty
rem reg has no /ifempty: delete %1 only when reg query lists no value or subkey.
reg query "%~1" >nul 2>nul || exit /b 0
for /f "delims=" %%l in ('reg query "%~1"') do if /i not "%%l"=="%~1" exit /b 0
reg delete "%~1" /f >nul 2>nul
exit /b 0

:usage
echo Usage: register-associations [/u]
exit /b 1

:missing
echo qcaret.exe is not beside this script.
exit /b 1

:failed
echo The registry refused a change.
exit /b 1
