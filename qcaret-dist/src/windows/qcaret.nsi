; QCaret setup for one Windows target. dist.xml runs makensis with:
;   VERSION     the version text (0.2.0-rc1)
;   VI_VERSION  four numbers for the file properties (0.2.0.0)
;   FILES       the include NsisFiles.java writes for the bundle tree
;   ART, ICON, LICENSE, OUTFILE
; Optional SIGN_INSTALLER and SIGN_UNINSTALLER name a command makensis runs
; on the build host with the file as its argument.
;
; Silent use: /S plus /MACHINE, /USER or /PORTABLE, optionally /DESKTOP, and
; optionally /D=<folder> as the last argument. /S alone installs for the
; current user. /DESKTOP adds the desktop shortcut to a regular install. A
; silent install never starts QCaret. A per-machine or per-user install never
; holds qcaret.conf, whose presence selects portable mode; only /PORTABLE writes
; it, empty, and keeps an existing one.

Unicode true
Target amd64-unicode
ManifestDPIAware true
RequestExecutionLevel highest
SetCompressor /SOLID lzma

!ifdef SIGN_UNINSTALLER
  !uninstfinalize '${SIGN_UNINSTALLER} "%1"' = 0
!endif
!ifdef SIGN_INSTALLER
  !finalize '${SIGN_INSTALLER} "%1"' = 0
!endif

!include MUI2.nsh
!include LogicLib.nsh
!include FileFunc.nsh
!include nsDialogs.nsh
!include "${FILES}"

!define UNINSTKEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\QCaret"
!define PROGID "QCaret.File"
!define APPKEY "Software\Classes\Applications\qcaret.exe"

; Runs macro m once per extension QCaret is offered for in Open With: the list
; in register-associations.cmd. No .bat or .cmd: with QCaret.File in their
; OpenWithProgids, ShellExecute fails to run them.
!macro FOR_EACH_EXTENSION m
  !insertmacro ${m} .txt
  !insertmacro ${m} .md
  !insertmacro ${m} .log
  !insertmacro ${m} .csv
  !insertmacro ${m} .xml
  !insertmacro ${m} .json
  !insertmacro ${m} .yml
  !insertmacro ${m} .yaml
  !insertmacro ${m} .ini
  !insertmacro ${m} .conf
  !insertmacro ${m} .cfg
  !insertmacro ${m} .properties
  !insertmacro ${m} .c
  !insertmacro ${m} .h
  !insertmacro ${m} .cpp
  !insertmacro ${m} .hpp
  !insertmacro ${m} .cs
  !insertmacro ${m} .java
  !insertmacro ${m} .py
  !insertmacro ${m} .rb
  !insertmacro ${m} .js
  !insertmacro ${m} .ts
  !insertmacro ${m} .css
  !insertmacro ${m} .html
  !insertmacro ${m} .sh
  !insertmacro ${m} .ps1
  !insertmacro ${m} .sql
!macroend

!macro ADD_OPEN_WITH ext
  WriteRegStr SHCTX "Software\Classes\${ext}\OpenWithProgids" "${PROGID}" ""
  WriteRegStr SHCTX "${APPKEY}\SupportedTypes" "${ext}" ""
!macroend

!macro REMOVE_OPEN_WITH ext
  DeleteRegValue SHCTX "Software\Classes\${ext}\OpenWithProgids" "${PROGID}"
  DeleteRegKey /ifempty SHCTX "Software\Classes\${ext}\OpenWithProgids"
  DeleteRegKey /ifempty SHCTX "Software\Classes\${ext}"
!macroend

Name "QCaret"
OutFile "${OUTFILE}"
BrandingText "QCaret ${VERSION}"
InstallDir ""
ShowInstDetails show
ShowUninstDetails show

VIProductVersion "${VI_VERSION}"
VIFileVersion "${VI_VERSION}"
VIAddVersionKey "ProductName" "QCaret"
VIAddVersionKey "ProductVersion" "${VERSION}"
VIAddVersionKey "FileVersion" "${VERSION}"
VIAddVersionKey "FileDescription" "QCaret ${VERSION} Setup"
VIAddVersionKey "CompanyName" "QCaret"
VIAddVersionKey "LegalCopyright" "Copyright 2026 Adam Dailey"
VIAddVersionKey "OriginalFilename" "qcaret-setup.exe"

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_WELCOMEFINISHPAGE_BITMAP "${ART}/welcome.bmp"
!define MUI_UNWELCOMEFINISHPAGE_BITMAP "${ART}/welcome.bmp"
!define MUI_HEADERIMAGE
!define MUI_HEADERIMAGE_RIGHT
!define MUI_HEADERIMAGE_BITMAP "${ART}/header.bmp"
!define MUI_HEADERIMAGE_UNBITMAP "${ART}/header.bmp"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_NOAUTOCLOSE
!define MUI_UNFINISHPAGE_NOAUTOCLOSE
; Apache-2.0 asks nothing of the user, so the license page asks for no
; acceptance.
!define MUI_LICENSEPAGE_BUTTON "$(^NextBtn)"
!define MUI_LICENSEPAGE_TEXT_BOTTOM "QCaret is distributed under the Apache License 2.0. Click Next to continue."
!define MUI_FINISHPAGE_RUN
!define MUI_FINISHPAGE_RUN_TEXT "&Run QCaret"
!define MUI_FINISHPAGE_RUN_FUNCTION RunQCaret
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Create a &desktop shortcut"
!define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION CreateDesktopShortcut

Var Mode ; machine, user or portable
Var DirGiven
Var IsAdmin
Var ScopeMachine
Var ScopeUser
Var ScopePortable
Var DesktopSwitch ; 1 when /DESKTOP was given

!insertmacro MUI_PAGE_WELCOME
!define MUI_PAGE_HEADER_TEXT "License"
!define MUI_PAGE_HEADER_SUBTEXT "The terms under which QCaret is distributed."
!insertmacro MUI_PAGE_LICENSE "${LICENSE}"
Page custom ScopePage ScopePageLeave
!define MUI_PAGE_CUSTOMFUNCTION_LEAVE DirectoryLeave
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_PAGE_CUSTOMFUNCTION_SHOW FinishShow
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_UNPAGE_FINISH

!insertmacro MUI_LANGUAGE "English"

; Pushes 1 when $INSTDIR is $0 or under it, else 0.
Function IsUnder
  StrLen $1 $0
  StrCpy $2 $INSTDIR $1
  ${If} $2 != $0
    Push 0
    Return
  ${EndIf}
  StrCpy $2 $INSTDIR 1 $1
  ${If} $2 == ""
  ${OrIf} $2 == "\"
    Push 1
  ${Else}
    Push 0
  ${EndIf}
FunctionEnd

; Sets $R9 to why $INSTDIR cannot take the $Mode install, or to "".
Function CheckDir
  StrCpy $R9 ""
  ${If} $Mode != portable
    ${If} ${FileExists} "$INSTDIR\qcaret.conf"
      StrCpy $R9 "$INSTDIR holds a portable QCaret (qcaret.conf). Choose another folder, or install as portable."
    ${EndIf}
    Return
  ${EndIf}
  ${If} ${FileExists} "$INSTDIR\uninstall.exe"
    StrCpy $R9 "$INSTDIR holds an installed QCaret. Choose another folder for the portable copy."
    Return
  ${EndIf}
  ${ForEach} $3 0 2 + 1
    ${If} $3 == 0
      StrCpy $0 $PROGRAMFILES64
    ${ElseIf} $3 == 1
      StrCpy $0 $PROGRAMFILES32
    ${Else}
      StrCpy $0 $WINDIR
    ${EndIf}
    Call IsUnder
    Pop $1
    ${If} $1 == 1
      StrCpy $R9 "A portable copy cannot go under $0: QCaret could not save its settings there."
      Return
    ${EndIf}
  ${Next}
  StrCpy $4 0
  ${IfNot} ${FileExists} "$INSTDIR\*.*"
    StrCpy $4 1
  ${EndIf}
  ClearErrors
  CreateDirectory "$INSTDIR"
  FileOpen $5 "$INSTDIR\qcaret-setup.tmp" w
  ${If} ${Errors}
    StrCpy $R9 "You cannot write to $INSTDIR. Choose a folder you can write to."
    ${If} $4 == 1
      RMDir "$INSTDIR"
    ${EndIf}
    Return
  ${EndIf}
  FileClose $5
  Delete "$INSTDIR\qcaret-setup.tmp"
FunctionEnd

; Sets $INSTDIR to the folder for $Mode unless /D gave one: the existing
; install's folder, else the default.
Function ApplyModeDir
  ${If} $DirGiven == 1
    Return
  ${EndIf}
  ${If} $Mode == machine
    ReadRegStr $0 HKLM "${UNINSTKEY}" "InstallLocation"
    ${If} $0 == ""
      StrCpy $0 "$PROGRAMFILES64\QCaret"
    ${EndIf}
  ${ElseIf} $Mode == user
    ReadRegStr $0 HKCU "${UNINSTKEY}" "InstallLocation"
    ${If} $0 == ""
      StrCpy $0 "$LOCALAPPDATA\Programs\QCaret"
    ${EndIf}
  ${Else}
    StrCpy $0 "$DOCUMENTS\QCaret"
  ${EndIf}
  StrCpy $INSTDIR $0
FunctionEnd

Function .onInit
  StrCpy $IsAdmin 0
  UserInfo::GetAccountType
  Pop $0
  ${If} $0 == "Admin"
    StrCpy $IsAdmin 1
  ${EndIf}
  StrCpy $DirGiven 0
  ${If} $INSTDIR != ""
    StrCpy $DirGiven 1
  ${EndIf}

  StrCpy $Mode ""
  ${GetParameters} $R0
  ClearErrors
  ${GetOptions} $R0 "/MACHINE" $R1
  ${IfNot} ${Errors}
    StrCpy $Mode machine
  ${EndIf}
  ClearErrors
  ${GetOptions} $R0 "/USER" $R1
  ${IfNot} ${Errors}
    StrCpy $Mode user
  ${EndIf}
  ClearErrors
  ${GetOptions} $R0 "/PORTABLE" $R1
  ${IfNot} ${Errors}
    StrCpy $Mode portable
  ${EndIf}
  StrCpy $DesktopSwitch 0
  ClearErrors
  ${GetOptions} $R0 "/DESKTOP" $R1
  ${IfNot} ${Errors}
    StrCpy $DesktopSwitch 1
  ${EndIf}

  ${If} $Mode == ""
    ReadRegStr $0 HKLM "${UNINSTKEY}" "InstallLocation"
    ReadRegStr $1 HKCU "${UNINSTKEY}" "InstallLocation"
    ${If} $0 != ""
    ${AndIf} $IsAdmin == 1
      StrCpy $Mode machine
    ${ElseIf} $1 != ""
      StrCpy $Mode user
    ${ElseIf} $IsAdmin == 1
    ${AndIfNot} ${Silent}
      StrCpy $Mode machine
    ${Else}
      StrCpy $Mode user
    ${EndIf}
  ${EndIf}

  ${If} $Mode == machine
  ${AndIf} $IsAdmin != 1
    ${If} ${Silent}
      SetErrorLevel 5
      Quit
    ${EndIf}
    StrCpy $Mode user
  ${EndIf}

  Call ApplyModeDir
  ${If} ${Silent}
    Call CheckDir
    ${If} $R9 != ""
      SetErrorLevel 2
      Quit
    ${EndIf}
  ${EndIf}
FunctionEnd

Function ScopePage
  !insertmacro MUI_HEADER_TEXT "Installation Type" "Choose who uses QCaret on this computer, or make a portable copy."
  nsDialogs::Create 1018
  Pop $0
  ${If} $0 == error
    Abort
  ${EndIf}
  ${NSD_CreateRadioButton} 0 0 100% 12u "For &all users (administrator rights required)"
  Pop $ScopeMachine
  ${NSD_CreateLabel} 12u 13u -12u 18u "Installs into Program Files with a Start menu shortcut, Open With for text and source files, and an entry in Installed apps."
  Pop $0
  ${NSD_CreateRadioButton} 0 38u 100% 12u "For &me only"
  Pop $ScopeUser
  ${NSD_CreateLabel} 12u 51u -12u 18u "Installs into your user profile with the same shortcut, Open With and Installed apps entries. No administrator rights needed."
  Pop $0
  ${NSD_CreateRadioButton} 0 76u 100% 12u "&Portable"
  Pop $ScopePortable
  ${NSD_CreateLabel} 12u 89u -12u 26u "Copies QCaret into a folder you choose and keeps its settings there. Nothing is registered with Windows: delete the folder to remove it."
  Pop $0
  ${If} $IsAdmin != 1
    EnableWindow $ScopeMachine 0
  ${EndIf}
  ${If} $Mode == machine
    ${NSD_Check} $ScopeMachine
  ${ElseIf} $Mode == user
    ${NSD_Check} $ScopeUser
  ${Else}
    ${NSD_Check} $ScopePortable
  ${EndIf}
  nsDialogs::Show
FunctionEnd

Function ScopePageLeave
  ${NSD_GetState} $ScopeMachine $0
  ${NSD_GetState} $ScopePortable $1
  ${If} $0 == ${BST_CHECKED}
    StrCpy $Mode machine
  ${ElseIf} $1 == ${BST_CHECKED}
    StrCpy $Mode portable
  ${Else}
    StrCpy $Mode user
  ${EndIf}
  Call ApplyModeDir
FunctionEnd

; A portable copy gets no desktop shortcut.
Function FinishShow
  ${If} $Mode == portable
    ShowWindow $mui.FinishPage.ShowReadme ${SW_HIDE}
  ${EndIf}
FunctionEnd

; Explorer starts the file through the running shell, so QCaret runs as the
; signed-in user without elevation even when setup is elevated.
Function RunQCaret
  Exec '"$WINDIR\explorer.exe" "$INSTDIR\qcaret.exe"'
FunctionEnd

; $DESKTOP is the common desktop for an all-users install (SetShellVarContext
; all) and the user's desktop otherwise.
Function CreateDesktopShortcut
  CreateShortcut "$DESKTOP\QCaret.lnk" "$INSTDIR\qcaret.exe" "" "$INSTDIR\qcaret.exe" 0
FunctionEnd

Function DirectoryLeave
  Call CheckDir
  ${If} $R9 != ""
    MessageBox MB_OK|MB_ICONEXCLAMATION "$R9"
    Abort
  ${EndIf}
FunctionEnd

Section "QCaret"
  ${If} $Mode == machine
    SetShellVarContext all
  ${Else}
    SetShellVarContext current
  ${EndIf}

  ; A regular install elsewhere in the same scope is removed first, so the
  ; registry names one folder.
  ${If} $Mode != portable
    ReadRegStr $0 SHCTX "${UNINSTKEY}" "InstallLocation"
    ${If} $0 != ""
    ${AndIf} $0 != $INSTDIR
    ${AndIf} ${FileExists} "$0\uninstall.exe"
      DetailPrint "Removing QCaret from $0"
      ExecWait '"$0\uninstall.exe" /S _?=$0'
      Delete "$0\uninstall.exe"
      RMDir "$0"
    ${EndIf}
  ${EndIf}

  ; An upgrade replaces the runtime and jars whole, so no file of the older
  ; version stays behind. qcaret.jar marks the folders as QCaret's.
  ${If} ${FileExists} "$INSTDIR\lib\qcaret.jar"
    RMDir /r "$INSTDIR\lib"
    RMDir /r "$INSTDIR\runtime"
  ${EndIf}
  !insertmacro QCARET_INSTALL_FILES

  ${If} $Mode == portable
    ${IfNot} ${FileExists} "$INSTDIR\qcaret.conf"
      FileOpen $0 "$INSTDIR\qcaret.conf" w
      FileClose $0
    ${EndIf}
    Return
  ${EndIf}

  WriteUninstaller "$INSTDIR\uninstall.exe"
  CreateShortcut "$SMPROGRAMS\QCaret.lnk" "$INSTDIR\qcaret.exe" "" "$INSTDIR\qcaret.exe" 0
  ${If} $DesktopSwitch == 1
    Call CreateDesktopShortcut
  ${EndIf}

  ; Open With for each listed extension; its default handler is untouched.
  WriteRegStr SHCTX "Software\Classes\${PROGID}" "" "Text file (QCaret)"
  WriteRegStr SHCTX "Software\Classes\${PROGID}\DefaultIcon" "" "$INSTDIR\qcaret.exe,0"
  WriteRegStr SHCTX "Software\Classes\${PROGID}\shell\open\command" "" '"$INSTDIR\qcaret.exe" "%1"'
  WriteRegStr SHCTX "${APPKEY}" "FriendlyAppName" "QCaret"
  WriteRegStr SHCTX "${APPKEY}\DefaultIcon" "" "$INSTDIR\qcaret.exe,0"
  WriteRegStr SHCTX "${APPKEY}\shell\open\command" "" '"$INSTDIR\qcaret.exe" "%1"'
  !insertmacro FOR_EACH_EXTENSION ADD_OPEN_WITH

  WriteRegStr SHCTX "${UNINSTKEY}" "DisplayName" "QCaret"
  WriteRegStr SHCTX "${UNINSTKEY}" "DisplayVersion" "${VERSION}"
  WriteRegStr SHCTX "${UNINSTKEY}" "Publisher" "QCaret"
  WriteRegStr SHCTX "${UNINSTKEY}" "DisplayIcon" "$INSTDIR\qcaret.exe,0"
  WriteRegStr SHCTX "${UNINSTKEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr SHCTX "${UNINSTKEY}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegStr SHCTX "${UNINSTKEY}" "QuietUninstallString" '"$INSTDIR\uninstall.exe" /S'
  WriteRegStr SHCTX "${UNINSTKEY}" "URLInfoAbout" "https://github.com/ajdailey/qcaret"
  WriteRegDWORD SHCTX "${UNINSTKEY}" "NoModify" 1
  WriteRegDWORD SHCTX "${UNINSTKEY}" "NoRepair" 1
  SetDetailsPrint none
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  SetDetailsPrint lastused
  WriteRegDWORD SHCTX "${UNINSTKEY}" "EstimatedSize" $0
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd

Function un.onInit
  ReadRegStr $0 HKLM "${UNINSTKEY}" "InstallLocation"
  ${If} $0 == $INSTDIR
    UserInfo::GetAccountType
    Pop $1
    ${If} $1 != "Admin"
      MessageBox MB_OK|MB_ICONSTOP "Removing QCaret for all users needs administrator rights." /SD IDOK
      SetErrorLevel 5
      Quit
    ${EndIf}
    SetShellVarContext all
  ${Else}
    SetShellVarContext current
  ${EndIf}
FunctionEnd

; Removes what the install wrote. Settings live outside $INSTDIR and stay.
Section "Uninstall"
  !insertmacro QCARET_UNINSTALL_FILES
  Delete "$INSTDIR\uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\QCaret.lnk"
  Delete "$DESKTOP\QCaret.lnk"

  DeleteRegKey SHCTX "Software\Classes\${PROGID}"
  DeleteRegKey SHCTX "${APPKEY}"
  !insertmacro FOR_EACH_EXTENSION REMOVE_OPEN_WITH
  DeleteRegKey SHCTX "${UNINSTKEY}"
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
SectionEnd
