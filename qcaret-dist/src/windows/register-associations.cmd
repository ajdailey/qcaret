@echo off
rem Adds QCaret to the current user's Open With lists, or removes it with /u.
rem Writes only under HKCU; the default program for a type stays the user's.
setlocal
set "exe=%~dp0qcaret.exe"
set "classes=HKCU\Software\Classes"
set "extensions=.txt .md .log .csv .xml .json .yml .yaml .ini .conf .cfg .properties .c .h .cpp .hpp .cs .java .py .rb .js .ts .css .html .sh .bat .cmd .ps1 .sql"

if /i "%~1"=="/u" goto remove
if not "%~1"=="" goto usage
if not exist "%exe%" goto missing

reg add "%classes%\QCaret.File" /ve /d "Text file (QCaret)" /f >nul || goto failed
reg add "%classes%\QCaret.File\DefaultIcon" /ve /d "\"%exe%\",0" /f >nul || goto failed
reg add "%classes%\QCaret.File\shell\open\command" /ve /d "\"%exe%\" \"%%1\"" /f >nul || goto failed
reg add "%classes%\Applications\qcaret.exe\shell\open\command" /ve /d "\"%exe%\" \"%%1\"" /f >nul || goto failed
for %%e in (%extensions%) do (
  reg add "%classes%\%%e\OpenWithProgids" /v "QCaret.File" /t REG_NONE /f >nul || goto failed
)
echo QCaret is in the Open With list for %extensions%
exit /b 0

:remove
for %%e in (%extensions%) do (
  reg delete "%classes%\%%e\OpenWithProgids" /v "QCaret.File" /f >nul 2>nul
)
reg delete "%classes%\QCaret.File" /f >nul 2>nul
reg delete "%classes%\Applications\qcaret.exe" /f >nul 2>nul
echo QCaret is out of the Open With lists.
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
