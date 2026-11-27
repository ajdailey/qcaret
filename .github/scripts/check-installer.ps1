# Installs the $env:TARGET setup from dist\ silently for all users and then for the current
# user. Each time it checks the Installed apps entry, the Start menu shortcut, no desktop
# shortcut, Open With for each extension register-associations.cmd lists with its default
# handler unchanged, the Explorer menu verb QCaret.Edit with each extension's default verb
# unchanged, that a .cmd still runs through ShellExecute, that the folder holds no
# qcaret.conf or portable.txt and that no QCaret process starts; runs check-bundle.ps1 against the
# installed folder; installs again with /DESKTOP over a stale runtime file, which the upgrade
# removes, and checks the desktop shortcut on that scope's desktop only; then uninstalls
# silently and checks that every file, shortcut and registry entry is gone and the per-user
# settings stay. A portable install with /DESKTOP then gets portable.txt and no qcaret.conf, no
# registry entry and no shortcut, starts with its settings in the folder, and an install over it
# keeps both files. Setup refuses a portable folder under Program Files and a regular install into
# a folder holding portable.txt or qcaret.conf. /NOEXPLORERMENU over an install removes the verb.
# register-associations.cmd from the portable zip, in a folder named with a space, & and (,
# adds Open With and the verb under HKCU, the verb on two selected files hands both to the
# running QCaret, and /u removes every key. Setup and its uninstaller request asInvoker. A
# standard local user installs for itself silently at medium integrity, is refused /MACHINE
# with exit 5 and a message, and uninstalls; with its caller's profile variables setup gets no
# LOCALAPPDATA and refuses a per-user install with exit 3. Both scopes installed side by side
# give two Installed apps keys, QCaret and QCaret.User, with distinct names, and each uninstaller
# removes only its own; a per-user install deletes the HKCU QCaret key an earlier version wrote.
# No uninstall or /u leaves a Software\Classes\* or *\shell key that was absent before. Run from
# the repository root with no QCaret installed.
$ErrorActionPreference = 'Stop'

$setup = (Get-Item "dist\qcaret-*-$env:TARGET-setup.exe").FullName
$version = (Split-Path -Leaf $setup) -replace '^qcaret-', '' -replace "-$env:TARGET-setup\.exe$", ''
$uninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\QCaret'
$userUninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\QCaret.User'
$commonDesktop = Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'QCaret.lnk'
$userDesktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'QCaret.lnk'
# The extensions setup offers QCaret for, the list register-associations.cmd holds.
$extensions = @(((Get-Content -LiteralPath 'qcaret-dist\src\windows\register-associations.cmd' |
  Where-Object { $_ -match '^set "extensions=' }) -replace '^set "extensions=', '' -replace '"$', '') -split ' ')
if ($extensions.Count -lt 2) { throw "no extension list in register-associations.cmd" }
$verbKey = 'Software\Classes\*\shell\QCaret.Edit'
$work = Join-Path $env:RUNNER_TEMP 'installer-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null

# Runs setup with $Arguments and returns its exit code.
function Invoke-Setup([string[]] $Arguments) {
  $process = Start-Process -FilePath $setup -ArgumentList $Arguments -PassThru
  if (-not $process.WaitForExit(300000)) { throw "setup $Arguments did not exit within 300 s" }
  $process.ExitCode
}

# The default value of HKEY_CLASSES_ROOT\$Extension, the extension's default handler; $null
# when the key is absent.
function Get-Handler([string] $Extension) {
  $key = Get-Item "Registry::HKEY_CLASSES_ROOT\$Extension" -ErrorAction SilentlyContinue
  if ($key) { $key.GetValue('') } else { $null }
}

Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;

public static class Assoc {
  [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
  static extern int AssocQueryString(int flags, int str, string assoc, string extra, StringBuilder output, ref int length);

  // ASSOCSTR_COMMAND of the default verb for extension; null when it has none.
  public static string Command(string extension) {
    int length = 4096;
    StringBuilder output = new StringBuilder(length);
    return AssocQueryString(0, 1, extension, null, output, ref length) == 0 ? output.ToString() : null;
  }
}
'@

# The default verb's command for each extension, as Explorer runs it on a double-click.
function Get-DefaultVerbs {
  $verbs = @{}
  foreach ($ext in $extensions) { $verbs[$ext] = [Assoc]::Command($ext) }
  $verbs
}

# Each extension in $Verbs with its default verb's command, on one line.
function Format-DefaultVerbs([hashtable] $Verbs) {
  ($extensions | ForEach-Object { "$_ [$($Verbs[$_])]" }) -join ' '
}

# Throws unless each extension's default verb's command is the one read before any install.
# With $Picker, while QCaret.File is in the OpenWithProgids, it may also be OpenWith.exe, and
# QCaret's own command where the type had no command or OpenWith.exe before (measured on
# windows-2025 and windows-11-arm): a type with no UserChoice resolves to Windows' app picker
# when it has a program of its own, and to the sole ProgID when it has none. The verb under *
# changes neither; the /NOEXPLORERMENU step checks that.
function Assert-DefaultVerbs([string] $What, [switch] $Picker) {
  $now = Get-DefaultVerbs
  Write-Output "default verbs after $What`: $(Format-DefaultVerbs $now)"
  foreach ($ext in $extensions) {
    $command = $now[$ext]
    $before = $defaultVerbs[$ext]
    if ($command -eq $before) { continue }
    $unowned = (-not $before) -or ($before -like '*\OpenWith.exe*')
    if ($Picker -and $command -like '*\OpenWith.exe*') { continue }
    if ($Picker -and $unowned -and $command -like '*qcaret*') { continue }
    throw "$What changed the $ext default verb from [$before] to [$command]"
  }
}

# Throws unless $Classes holds the QCaret.Edit verb for $Exe. The key path holds *, so every
# registry read takes -LiteralPath.
function Assert-Verb([string] $Classes, [string] $Exe, [string] $What) {
  $key = Get-Item -LiteralPath "$Classes\*\shell\QCaret.Edit" -ErrorAction SilentlyContinue
  if (-not $key) { throw "$What wrote no $Classes\*\shell\QCaret.Edit" }
  if ($key.GetValue('MUIVerb') -ne 'Edit in QCaret') { throw "$What QCaret.Edit MUIVerb is $($key.GetValue('MUIVerb'))" }
  if ($key.GetValue('Icon') -ne "`"$Exe`",0") { throw "$What QCaret.Edit Icon is $($key.GetValue('Icon'))" }
  $command = (Get-Item -LiteralPath "$Classes\*\shell\QCaret.Edit\command").GetValue('')
  if ($command -ne "`"$Exe`" `"%1`"") { throw "$What QCaret.Edit runs $command" }
}

function Test-Verb([string] $Hive) {
  Test-Path -LiteralPath "$Hive\$verbKey"
}

# Prints the values and subkeys of .log and .txt under each hive's Software\Classes.
function Show-Extensions([string] $When) {
  foreach ($hive in @('HKLM:', 'HKCU:')) {
    foreach ($ext in @('.log', '.txt')) {
      $key = Get-Item "$hive\Software\Classes\$ext" -ErrorAction SilentlyContinue
      if ($key) {
        Write-Output "$When $hive $ext values: $($key.GetValueNames() -join ', ') subkeys: $($key.GetSubKeyNames() -join ', ')"
      } else {
        Write-Output "$When $hive $ext absent"
      }
    }
  }
}

# Throws when a process runs an executable under $Dir. Setup has exited, so a launch it
# started shows within the 5 s wait.
function Assert-NotRunning([string] $Dir, [string] $What) {
  Start-Sleep -Seconds 5
  $running = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$Dir\", [StringComparison]::OrdinalIgnoreCase) })
  if ($running.Count -gt 0) { throw "$What started $($running[0].ExecutablePath)" }
}

function Get-ShortcutTarget([string] $Path) {
  (New-Object -ComObject WScript.Shell).CreateShortcut($Path).TargetPath
}

# Throws unless ShellExecute runs a batch file: QCaret.File in .cmd OpenWithProgids makes it
# fail.
function Assert-CmdRuns([string] $What) {
  $cmd = Join-Path $work 'ran.cmd'
  $out = Join-Path $work 'ran.txt'
  Set-Content -LiteralPath $cmd -Value "@echo ran>`"%~dp0ran.txt`""
  Remove-Item -LiteralPath $out -ErrorAction SilentlyContinue
  $process = Start-Process -FilePath $cmd -PassThru
  if (-not $process.WaitForExit(30000)) { throw "$What`: ran.cmd did not exit within 30 s" }
  if (-not (Test-Path $out)) { throw "$What`: ShellExecute did not run ran.cmd" }
}

# Waits up to 120 s for $Condition to hold; throws $Message otherwise.
function Wait-Until([scriptblock] $Condition, [string] $Message) {
  $deadline = (Get-Date).AddSeconds(120)
  while (-not (& $Condition)) {
    if ((Get-Date) -gt $deadline) { throw $Message }
    Start-Sleep -Milliseconds 500
  }
}

$defaultVerbs = Get-DefaultVerbs
Write-Output "default verbs before any install: $(Format-DefaultVerbs $defaultVerbs)"

# The verb's parents, which uninstall and /u delete only when empty.
$starKeys = @('HKLM:\Software\Classes\*', 'HKLM:\Software\Classes\*\shell', 'HKCU:\Software\Classes\*', 'HKCU:\Software\Classes\*\shell')
$starBefore = @{}
foreach ($key in $starKeys) {
  $starBefore[$key] = Test-Path -LiteralPath $key
  Write-Output "before any install: $key $(if ($starBefore[$key]) { 'present' } else { 'absent' })"
}

function Assert-NoNewStarKeys([string] $What) {
  foreach ($key in $starKeys) {
    if (-not $starBefore[$key] -and (Test-Path -LiteralPath $key)) { throw "$What left $key, absent before any install" }
  }
}

# The requestedExecutionLevel of the manifest in $Exe.
function Get-ExecutionLevel([string] $Exe) {
  $text = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($Exe))
  $found = [regex]::Matches($text, 'requestedExecutionLevel\s+level="(\w+)"')
  if ($found.Count -ne 1) { throw "$Exe holds $($found.Count) requestedExecutionLevel entries" }
  $found[0].Groups[1].Value
}

$level = Get-ExecutionLevel $setup
if ($level -ne 'asInvoker') { throw "setup requests $level, expected asInvoker" }
Write-Output 'PASS: setup requests asInvoker'

function Test-Scope([string] $Scope) {
  if ($Scope -eq 'machine') {
    $hive = 'HKLM:'
    $dir = Join-Path $env:ProgramFiles 'QCaret'
    $shortcut = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\QCaret.lnk'
    $switch = '/MACHINE'
    $key = $uninstallKey
    $name = 'QCaret'
    $desktop = $commonDesktop
    $otherDesktop = $userDesktop
  } else {
    $hive = 'HKCU:'
    $dir = Join-Path $env:LOCALAPPDATA 'Programs\QCaret'
    $shortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\QCaret.lnk'
    $switch = '/USER'
    $key = $userUninstallKey
    $name = 'QCaret (current user)'
    $desktop = $userDesktop
    $otherDesktop = $commonDesktop
  }
  $classes = "$hive\Software\Classes"
  $handlers = @{}
  foreach ($ext in $extensions) { $handlers[$ext] = Get-Handler $ext }
  Show-Extensions "before $Scope install:"

  $code = Invoke-Setup @('/S', $switch)
  if ($code -ne 0) { throw "$Scope setup exited $code" }
  Assert-NotRunning $dir "silent $Scope setup"
  $entry = Get-ItemProperty "$hive\$key"
  if ($entry.DisplayName -ne $name) { throw "$Scope DisplayName is $($entry.DisplayName), expected $name" }
  foreach ($other in @("HKLM:\$uninstallKey", "HKLM:\$userUninstallKey", "HKCU:\$uninstallKey", "HKCU:\$userUninstallKey")) {
    if ($other -ne "$hive\$key" -and (Test-Path $other)) { throw "$Scope install wrote $other" }
  }
  if ($entry.InstallLocation -ne $dir) { throw "$Scope InstallLocation is $($entry.InstallLocation), expected $dir" }
  if ($entry.DisplayVersion -ne $version) { throw "$Scope DisplayVersion is $($entry.DisplayVersion), expected $version" }
  if ($entry.DisplayIcon -ne "$dir\qcaret.exe,0") { throw "$Scope DisplayIcon is $($entry.DisplayIcon)" }
  if ($entry.QuietUninstallString -ne "`"$dir\uninstall.exe`" /S") { throw "$Scope QuietUninstallString is $($entry.QuietUninstallString)" }
  foreach ($file in @('qcaret.exe', 'qcaret.cmd', 'uninstall.exe', 'lib\qcaret.jar', 'runtime\bin\javaw.exe', 'LICENSE')) {
    if (-not (Test-Path (Join-Path $dir $file))) { throw "$Scope install lacks $file" }
  }
  foreach ($file in @('qcaret.conf', 'portable.txt')) {
    if (Test-Path (Join-Path $dir $file)) { throw "$Scope install holds $file" }
  }
  if ((Get-ShortcutTarget $shortcut) -ne "$dir\qcaret.exe") { throw "$Scope shortcut $shortcut does not start $dir\qcaret.exe" }
  foreach ($path in @($commonDesktop, $userDesktop)) {
    if (Test-Path $path) { throw "$Scope install without /DESKTOP wrote $path" }
  }
  foreach ($ext in $extensions) {
    $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids"
    if ($null -eq $progids.'QCaret.File') { throw "$Scope $ext has no QCaret.File in OpenWithProgids" }
    if ((Get-Handler $ext) -ne $handlers[$ext]) { throw "$Scope install changed the $ext handler to $(Get-Handler $ext)" }
  }
  $command = (Get-Item "$classes\QCaret.File\shell\open\command").GetValue('')
  if ($command -ne "`"$dir\qcaret.exe`" `"%1`"") { throw "$Scope QCaret.File opens with $command" }
  Assert-Verb $classes "$dir\qcaret.exe" "$Scope install"
  $level = Get-ExecutionLevel (Join-Path $dir 'uninstall.exe')
  if ($level -ne 'asInvoker') { throw "$Scope uninstall.exe requests $level, expected asInvoker" }
  Assert-DefaultVerbs "$Scope install" -Picker
  Assert-CmdRuns "$Scope install"
  Write-Output "PASS: $Scope install into $dir with its Installed apps entry, shortcut, Open With and Explorer menu verb, no desktop shortcut, QCaret as the default verb only of types with no program, a .cmd that still runs, and no QCaret started"

  $env:QCARET_HOME = $dir
  try { & .github\scripts\check-bundle.ps1 } finally { Remove-Item Env:QCARET_HOME }

  # An install over the last one replaces the runtime whole and adds no qcaret.conf; /DESKTOP
  # adds the shortcut to this scope's desktop only.
  $stale = Join-Path $dir 'runtime\bin\stale.dll'
  Set-Content -LiteralPath $stale -Value 'mock'
  $code = Invoke-Setup @('/S', $switch, '/DESKTOP')
  if ($code -ne 0) { throw "$Scope setup over the install exited $code" }
  if (Test-Path $stale) { throw "$Scope upgrade left $stale" }
  if (Test-Path (Join-Path $dir 'qcaret.conf')) { throw "$Scope upgrade added qcaret.conf" }
  if ((Get-ShortcutTarget $desktop) -ne "$dir\qcaret.exe") { throw "$Scope /DESKTOP shortcut $desktop does not start $dir\qcaret.exe" }
  if (Test-Path $otherDesktop) { throw "$Scope /DESKTOP wrote $otherDesktop" }
  Assert-Verb $classes "$dir\qcaret.exe" "$Scope install over the last one"
  Assert-NotRunning $dir "silent $Scope setup over the install"
  Write-Output "PASS: $Scope install over the last one removed the stale runtime file, added no qcaret.conf, and /DESKTOP wrote $desktop"

  $settings = Join-Path $env:APPDATA 'QCaret'
  New-Item -ItemType Directory -Path $settings -Force | Out-Null
  $marker = Join-Path $settings 'qcaret.conf'
  Set-Content -LiteralPath $marker -Value 'version=1'
  # The uninstaller runs from a copy in %TEMP% and returns at once.
  Start-Process -FilePath (Join-Path $dir 'uninstall.exe') -ArgumentList '/S' -Wait
  Wait-Until { -not (Test-Path $dir) } "$Scope uninstall left $dir"
  Wait-Until { -not (Test-Path "$hive\$key") } "$Scope uninstall left the Installed apps entry"
  Show-Extensions "after $Scope uninstall:"
  Wait-Until { -not (Test-Path $shortcut) } "$Scope uninstall left $shortcut"
  Wait-Until { -not (Test-Path $desktop) } "$Scope uninstall left $desktop"
  foreach ($key in @('QCaret.File', 'Applications\qcaret.exe')) {
    Wait-Until { -not (Test-Path "$classes\$key") } "$Scope uninstall left $classes\$key"
  }
  Wait-Until { -not (Test-Verb $hive) } "$Scope uninstall left $hive\$verbKey"
  Assert-NoNewStarKeys "$Scope uninstall"
  Assert-DefaultVerbs "$Scope uninstall"
  foreach ($ext in $extensions) {
    $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids" -ErrorAction SilentlyContinue
    if ($progids -and $null -ne $progids.'QCaret.File') { throw "$Scope uninstall left QCaret.File in $ext OpenWithProgids" }
    if ((Get-Handler $ext) -ne $handlers[$ext]) { throw "$Scope uninstall changed the $ext handler" }
  }
  if (-not (Test-Path $marker)) { throw "$Scope uninstall removed the per-user settings" }
  Remove-Item -Recurse -Force $settings
  Write-Output "PASS: $Scope uninstall removed every file, shortcut and registry entry, left no new * or *\shell key, restored the default verbs, and kept the settings"
}

Test-Scope 'machine'
Test-Scope 'user'

# Both scopes side by side, over the HKCU QCaret key a per-user install of an earlier version
# wrote: the per-user install deletes it, the two entries have distinct names, and each
# uninstaller removes only its own install and key.
$machineDir = Join-Path $env:ProgramFiles 'QCaret'
$userDir = Join-Path $env:LOCALAPPDATA 'Programs\QCaret'
$code = Invoke-Setup @('/S', '/MACHINE')
if ($code -ne 0) { throw "all-users setup beside a per-user one exited $code" }
New-Item -Path "HKCU:\$uninstallKey" -Force | Out-Null
New-ItemProperty -Path "HKCU:\$uninstallKey" -Name 'DisplayName' -Value 'QCaret' | Out-Null
New-ItemProperty -Path "HKCU:\$uninstallKey" -Name 'InstallLocation' -Value $userDir | Out-Null
$code = Invoke-Setup @('/S', '/USER')
if ($code -ne 0) { throw "per-user setup beside an all-users one exited $code" }
if (Test-Path "HKCU:\$uninstallKey") { throw "per-user setup left the earlier version's HKCU:\$uninstallKey" }
$machineEntry = Get-ItemProperty "HKLM:\$uninstallKey"
$userEntry = Get-ItemProperty "HKCU:\$userUninstallKey"
if ($machineEntry.DisplayName -ne 'QCaret' -or $machineEntry.InstallLocation -ne $machineDir) { throw "side by side, the all-users entry is [$($machineEntry.DisplayName)] at [$($machineEntry.InstallLocation)]" }
if ($userEntry.DisplayName -ne 'QCaret (current user)' -or $userEntry.InstallLocation -ne $userDir) { throw "side by side, the per-user entry is [$($userEntry.DisplayName)] at [$($userEntry.InstallLocation)]" }
Start-Process -FilePath (Join-Path $machineDir 'uninstall.exe') -ArgumentList '/S' -Wait
Wait-Until { -not (Test-Path $machineDir) } "all-users uninstall beside a per-user install left $machineDir"
Wait-Until { -not (Test-Path "HKLM:\$uninstallKey") } 'all-users uninstall beside a per-user install left its entry'
if (-not (Test-Path (Join-Path $userDir 'qcaret.exe'))) { throw "all-users uninstall removed the per-user install in $userDir" }
if ((Get-ItemProperty "HKCU:\$userUninstallKey" -ErrorAction SilentlyContinue).InstallLocation -ne $userDir) { throw 'all-users uninstall removed the per-user entry' }
Assert-Verb 'HKCU:\Software\Classes' "$userDir\qcaret.exe" 'per-user install after the all-users uninstall'
Start-Process -FilePath (Join-Path $userDir 'uninstall.exe') -ArgumentList '/S' -Wait
Wait-Until { -not (Test-Path $userDir) } "per-user uninstall left $userDir"
Wait-Until { -not (Test-Path "HKCU:\$userUninstallKey") } 'per-user uninstall left its entry'
Assert-NoNewStarKeys 'side-by-side uninstalls'
Write-Output "PASS: side by side, the entries QCaret and QCaret (current user) each removed only their own install, and the per-user install deleted the earlier version's HKCU QCaret key"

# A standard user runs standard.ps1 from a folder it can write: setup /S /USER, whose token
# it reads while setup runs; setup /S /MACHINE with standard error captured; the uninstaller.
# It writes result.json beside itself.
$standardScript = @'
$ErrorActionPreference = 'Stop'
$result = [ordered]@{}
try {
  Add-Type -TypeDefinition @"
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class TokenInfo {
  [DllImport("advapi32.dll", SetLastError = true)]
  static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
  [DllImport("advapi32.dll", SetLastError = true)]
  static extern bool GetTokenInformation(IntPtr token, int infoClass, IntPtr info, int length, out int returned);
  [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
  [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthority(IntPtr sid, int index);
  [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

  // Class 25 TokenIntegrityLevel: the level's RID (0x2000 medium, 0x3000 high).
  // Class 20 TokenElevation: 1 elevated, 0 not.
  public static int Query(IntPtr process, int infoClass) {
    IntPtr token;
    if (!OpenProcessToken(process, 0x0008, out token)) { throw new Win32Exception(); }
    IntPtr buffer = Marshal.AllocHGlobal(256);
    try {
      int returned;
      // TokenElevation takes exactly its DWORD's length.
      if (!GetTokenInformation(token, infoClass, buffer, infoClass == 25 ? 256 : 4, out returned)) { throw new Win32Exception(); }
      if (infoClass != 25) { return Marshal.ReadInt32(buffer); }
      IntPtr sid = Marshal.ReadIntPtr(buffer);
      int count = Marshal.ReadByte(GetSidSubAuthorityCount(sid));
      return Marshal.ReadInt32(GetSidSubAuthority(sid, count - 1));
    } finally {
      Marshal.FreeHGlobal(buffer);
      CloseHandle(token);
    }
  }
}
"@
  # Start-Process -Credential passes the caller's environment. With the caller's profile
  # variables Windows gives setup no LOCALAPPDATA, which a per-user install refuses.
  $err = Join-Path $PSScriptRoot 'noappdata.err'
  $process = Start-Process -FilePath (Join-Path $PSScriptRoot 'setup.exe') -ArgumentList '/S', '/USER' -RedirectStandardError $err -PassThru
  $null = $process.Handle
  $process.WaitForExit()
  $result.noAppDataExit = $process.ExitCode
  $result.noAppDataError = (Get-Content -LiteralPath $err -Raw)
  $result.noAppDataEntry = Test-Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\QCaret.User'
  $result.noAppDataDir = Test-Path (Join-Path $env:SystemDrive 'Programs\QCaret')
  # Point the profile variables at this user's profile so setup and the checks use its folders.
  $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
  $userHome = (Get-ItemProperty "HKLM:\SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList\$sid").ProfileImagePath
  $env:USERPROFILE = $userHome
  $env:HOMEPATH = $userHome.Substring(2)
  $env:USERNAME = [Environment]::UserName
  $env:APPDATA = Join-Path $userHome 'AppData\Roaming'
  $env:LOCALAPPDATA = Join-Path $userHome 'AppData\Local'
  $env:TEMP = Join-Path $env:LOCALAPPDATA 'Temp'
  $env:TMP = $env:TEMP
  New-Item -ItemType Directory -Path $env:TEMP -Force | Out-Null
  $result.localAppData = $env:LOCALAPPDATA
  $setup = Join-Path $PSScriptRoot 'setup.exe'
  $installed = Join-Path $env:LOCALAPPDATA 'Programs\QCaret'
  $uninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\QCaret.User'
  $stars = @('HKCU:\Software\Classes\*', 'HKCU:\Software\Classes\*\shell')
  $result.starBefore = @($stars | Where-Object { Test-Path -LiteralPath $_ })
  $result.callerLevel = [TokenInfo]::Query([Diagnostics.Process]::GetCurrentProcess().Handle, 25)
  $process = Start-Process -FilePath $setup -ArgumentList '/S', '/USER' -PassThru
  $result.setupLevel = [TokenInfo]::Query($process.Handle, 25)
  $result.setupElevation = [TokenInfo]::Query($process.Handle, 20)
  $process.WaitForExit()
  $result.userExit = $process.ExitCode
  $result.installed = Test-Path (Join-Path $installed 'qcaret.exe')
  $result.entry = (Get-ItemProperty $uninstallKey -ErrorAction SilentlyContinue).InstallLocation
  $result.verb = Test-Path -LiteralPath 'HKCU:\Software\Classes\*\shell\QCaret.Edit'
  $err = Join-Path $PSScriptRoot 'machine.err'
  $process = Start-Process -FilePath $setup -ArgumentList '/S', '/MACHINE' -RedirectStandardError $err -PassThru
  $null = $process.Handle
  $process.WaitForExit()
  $result.machineExit = $process.ExitCode
  $result.machineError = (Get-Content -LiteralPath $err -Raw)
  $result.programFiles = Test-Path (Join-Path $env:ProgramFiles 'QCaret')
  Start-Process -FilePath (Join-Path $installed 'uninstall.exe') -ArgumentList '/S' -Wait
  $deadline = (Get-Date).AddSeconds(120)
  while ((Test-Path $installed) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 500 }
  $result.removed = -not (Test-Path $installed)
  $result.entryAfter = Test-Path $uninstallKey
  $result.starAfter = @($stars | Where-Object { Test-Path -LiteralPath $_ })
} catch {
  $result.error = "$_"
}
$result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'result.json')
'@

$account = 'qcaretstd'
# net user asks for confirmation of a password over 14 characters.
$password = 'Qa1!' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$shared = Join-Path $env:SystemDrive 'qcaret-standard'
if (Test-Path $shared) { Remove-Item -Recurse -Force $shared }
New-Item -ItemType Directory -Path $shared | Out-Null
Copy-Item -LiteralPath $setup -Destination (Join-Path $shared 'setup.exe')
Set-Content -LiteralPath (Join-Path $shared 'standard.ps1') -Value $standardScript
# S-1-5-32-545 is BUILTIN\Users.
& icacls.exe $shared /grant '*S-1-5-32-545:(OI)(CI)M' | Out-Null
if ($LASTEXITCODE -ne 0) { throw "icacls exited $LASTEXITCODE" }
& net.exe user $account $password /add /expires:never | Out-Null
if ($LASTEXITCODE -ne 0) { throw "net user /add exited $LASTEXITCODE" }
try {
  $credential = New-Object System.Management.Automation.PSCredential($account, (ConvertTo-SecureString $password -AsPlainText -Force))
  $process = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList '-NoProfile', '-NonInteractive', '-File', (Join-Path $shared 'standard.ps1') -Credential $credential -LoadUserProfile -WorkingDirectory $shared -PassThru
  if (-not $process.WaitForExit(600000)) { throw 'the standard user script did not exit within 600 s' }
  $resultFile = Join-Path $shared 'result.json'
  if (-not (Test-Path $resultFile)) { throw 'the standard user script wrote no result.json' }
  Write-Output "standard user result: $(Get-Content -LiteralPath $resultFile -Raw)"
  $result = Get-Content -LiteralPath $resultFile -Raw | ConvertFrom-Json
  if ($result.error) { throw "the standard user script failed: $($result.error)" }
  if ($result.noAppDataExit -ne 3) { throw "standard user setup /S /USER without LOCALAPPDATA exited $($result.noAppDataExit), expected 3" }
  if ("$($result.noAppDataError)" -notlike '*no local application data folder*') { throw "standard user setup /S /USER without LOCALAPPDATA wrote [$($result.noAppDataError)] to standard error" }
  if ($result.noAppDataEntry -or $result.noAppDataDir) { throw 'standard user setup /S /USER without LOCALAPPDATA wrote its entry or \Programs\QCaret' }
  if ($result.callerLevel -ne 0x2000) { throw "the standard user's shell runs at integrity $($result.callerLevel), expected 0x2000" }
  if ($result.setupLevel -ne 0x2000 -or $result.setupElevation -ne 0) { throw "standard user setup ran at integrity $($result.setupLevel), elevation $($result.setupElevation)" }
  if ($result.userExit -ne 0) { throw "standard user setup /S /USER exited $($result.userExit)" }
  if (-not $result.installed) { throw 'standard user setup /S /USER installed no qcaret.exe' }
  if ($result.entry -ne (Join-Path $result.localAppData 'Programs\QCaret')) { throw "standard user setup /S /USER wrote InstallLocation [$($result.entry)]" }
  if (-not $result.verb) { throw 'standard user setup /S /USER wrote no QCaret.Edit verb' }
  if ($result.machineExit -ne 5) { throw "standard user setup /S /MACHINE exited $($result.machineExit), expected 5" }
  if ("$($result.machineError)" -notlike '*/MACHINE needs an elevated caller*') { throw "standard user setup /S /MACHINE wrote [$($result.machineError)] to standard error" }
  if ($result.programFiles) { throw 'standard user setup /S /MACHINE wrote into Program Files' }
  if (-not $result.removed -or $result.entryAfter) { throw 'standard user uninstall left the folder or the Installed apps entry' }
  foreach ($key in @($result.starAfter)) {
    if (@($result.starBefore) -notcontains $key) { throw "standard user uninstall left $key, absent before the install" }
  }
} finally {
  & net.exe user $account /delete | Out-Null
  Get-CimInstance Win32_UserProfile | Where-Object { $_.LocalPath -and (Split-Path -Leaf $_.LocalPath) -like "$account*" } |
    Remove-CimInstance -ErrorAction SilentlyContinue
  Remove-Item -Recurse -Force $shared -ErrorAction SilentlyContinue
}
Write-Output 'PASS: a standard user installed for itself at medium integrity without elevation, setup /S /USER with no LOCALAPPDATA exited 3 and /S /MACHINE exited 5 with their messages, and the uninstall left no new * or *\shell key'

# /NOEXPLORERMENU clears the Explorer menu component: an install over one with the verb
# removes it and keeps Open With, and the default verbs read the same with and without it.
$dir = Join-Path $env:LOCALAPPDATA 'Programs\QCaret'
$code = Invoke-Setup @('/S', '/USER')
if ($code -ne 0) { throw "per-user setup exited $code" }
Assert-Verb 'HKCU:\Software\Classes' "$dir\qcaret.exe" 'per-user install'
$withVerb = Get-DefaultVerbs
$code = Invoke-Setup @('/S', '/USER', '/NOEXPLORERMENU')
if ($code -ne 0) { throw "per-user setup with /NOEXPLORERMENU exited $code" }
foreach ($hive in @('HKLM:', 'HKCU:')) {
  if (Test-Verb $hive) { throw "per-user install with /NOEXPLORERMENU left $hive\$verbKey" }
}
Assert-NoNewStarKeys 'per-user install with /NOEXPLORERMENU'
if ($null -eq (Get-ItemProperty 'HKCU:\Software\Classes\.log\OpenWithProgids').'QCaret.File') { throw 'per-user install with /NOEXPLORERMENU dropped Open With' }
$withoutVerb = Get-DefaultVerbs
foreach ($ext in $extensions) {
  if ($withVerb[$ext] -ne $withoutVerb[$ext]) { throw "the verb changed the $ext default verb: [$($withVerb[$ext])] with it, [$($withoutVerb[$ext])] without" }
}
Start-Process -FilePath (Join-Path $dir 'uninstall.exe') -ArgumentList '/S' -Wait
Wait-Until { -not (Test-Path $dir) } "per-user uninstall left $dir"
Wait-Until { -not (Test-Path "HKCU:\$userUninstallKey") } 'per-user uninstall left the Installed apps entry'
Write-Output 'PASS: /NOEXPLORERMENU over a per-user install removed the Explorer menu verb, kept Open With, and left the default verbs as they were with the verb'

# Portable: portable.txt, nothing registered, settings in the folder.
$portable = Join-Path $work 'QCaret Portable'
$code = Invoke-Setup @('/S', '/PORTABLE', '/DESKTOP', "/D=$portable")
if ($code -ne 0) { throw "portable setup exited $code" }
Assert-NotRunning $portable 'silent portable setup'
$marker = Join-Path $portable 'portable.txt'
$conf = Join-Path $portable 'qcaret.conf'
if (-not (Test-Path -PathType Leaf $marker)) { throw 'portable install wrote no portable.txt' }
if (@(Get-Content -LiteralPath $marker).Count -ne 1) { throw 'portable.txt is not one line' }
if (Test-Path $conf) { throw 'portable install wrote qcaret.conf' }
if (Test-Path (Join-Path $portable 'uninstall.exe')) { throw 'portable install wrote uninstall.exe' }
foreach ($hive in @('HKLM:', 'HKCU:')) {
  if (Test-Path "$hive\$uninstallKey") { throw "portable install wrote $hive\$uninstallKey" }
  if (Test-Path "$hive\$userUninstallKey") { throw "portable install wrote $hive\$userUninstallKey" }
  if (Test-Path "$hive\Software\Classes\QCaret.File") { throw "portable install wrote $hive\Software\Classes\QCaret.File" }
}
foreach ($shortcut in @((Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\QCaret.lnk'), (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\QCaret.lnk'), $commonDesktop, $userDesktop)) {
  if (Test-Path $shortcut) { throw "portable install wrote $shortcut" }
}

$before = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" | ForEach-Object { [int] $_.ProcessId })
Start-Process -FilePath (Join-Path $portable 'qcaret.exe') -ArgumentList '-l' | Out-Null
$log = Join-Path $portable 'logs\qcaret.log'
try {
  Wait-Until { (Test-Path $log) -and (Get-Content -LiteralPath $log -Raw).Contains('Main window open') } "portable QCaret wrote no 'Main window open' to $log"
} finally {
  Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
    Where-Object { $before -notcontains [int] $_.ProcessId } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Write-Output "PASS: portable install with /DESKTOP wrote portable.txt and no qcaret.conf, registered nothing, wrote no shortcut, and QCaret kept its log in $portable"

Set-Content -LiteralPath $conf -Value 'ui.theme=dark'
$stale = Join-Path $portable 'runtime\bin\stale.dll'
Set-Content -LiteralPath $stale -Value 'mock'
$code = Invoke-Setup @('/S', '/PORTABLE', "/D=$portable")
if ($code -ne 0) { throw "portable setup over the portable folder exited $code" }
if ((Get-Content -LiteralPath $conf -Raw).Trim() -ne 'ui.theme=dark') { throw 'portable upgrade changed qcaret.conf' }
if (-not (Test-Path $marker)) { throw 'portable upgrade removed portable.txt' }
if (-not (Test-Path $log)) { throw 'portable upgrade removed the logs' }
if (Test-Path $stale) { throw "portable upgrade left $stale" }
Write-Output 'PASS: portable install over the folder kept portable.txt, qcaret.conf and the logs and replaced the runtime'

# A folder holding either file is portable, so a regular install refuses it.
Remove-Item -LiteralPath $conf
$legacy = Join-Path $work 'QCaret Legacy'
New-Item -ItemType Directory -Path $legacy | Out-Null
New-Item -ItemType File -Path (Join-Path $legacy 'qcaret.conf') | Out-Null
foreach ($folder in @($portable, $legacy)) {
  $code = Invoke-Setup @('/S', '/USER', "/D=$folder")
  if ($code -ne 2) { throw "a per-user install into $folder exited $code, expected 2" }
  if (Test-Path "HKCU:\$userUninstallKey") { throw "the refused per-user install into $folder wrote its Installed apps entry" }
}
if (Test-Path (Join-Path $legacy 'qcaret.exe')) { throw "the refused per-user install wrote into $legacy" }
$refused = Join-Path $env:ProgramFiles 'QCaret Portable'
$code = Invoke-Setup @('/S', '/PORTABLE', "/D=$refused")
if ($code -ne 2) { throw "a portable install under Program Files exited $code, expected 2" }
if (Test-Path $refused) { throw "the refused portable install created $refused" }
Write-Output 'PASS: setup refused a per-user install into a folder holding portable.txt or qcaret.conf and a portable folder under Program Files'
Remove-Item -Recurse -Force $portable, $legacy

# register-associations.cmd in the portable zip, unpacked into a folder whose name holds a
# space, & and (.
$unpacked = Join-Path $work 'Q & Caret (zip)'
Expand-Archive -LiteralPath (Get-Item "dist\qcaret-*-$env:TARGET-portable.zip").FullName -DestinationPath $unpacked
$tree = Join-Path $unpacked 'qcaret'
$exe = Join-Path $tree 'qcaret.exe'
$script = Join-Path $tree 'register-associations.cmd'
$classes = 'HKCU:\Software\Classes'

# Runs the script with $Arguments through cmd.exe /c ""<script>" <arguments>"; throws unless
# it exits 0 and prints $Expected.
function Invoke-Register([string] $Arguments, [string] $Expected) {
  $out = Join-Path $work 'register.out'
  $process = Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList "/d /c `"`"$script`" $Arguments`"" -RedirectStandardOutput $out -NoNewWindow -Wait -PassThru
  $text = (Get-Content -LiteralPath $out -Raw).Trim()
  if ($process.ExitCode -ne 0) { throw "register-associations.cmd $Arguments exited $($process.ExitCode): $text" }
  if ($text -cne $Expected) { throw "register-associations.cmd $Arguments printed [$text], expected [$Expected]" }
}

Invoke-Register '' "QCaret is in the Open With list for $($extensions -join ' ') and in the Explorer right-click menu."
$command = "`"$exe`" `"%1`""
foreach ($key in @('QCaret.File\shell\open\command', 'Applications\qcaret.exe\shell\open\command')) {
  $value = (Get-Item -LiteralPath "$classes\$key").GetValue('')
  if ($value -ne $command) { throw "register-associations.cmd wrote $key as $value" }
}
if ((Get-Item -LiteralPath "$classes\QCaret.File\DefaultIcon").GetValue('') -ne "`"$exe`",0") { throw 'register-associations.cmd wrote the wrong QCaret.File icon' }
foreach ($ext in $extensions) {
  if ($null -eq (Get-ItemProperty "$classes\$ext\OpenWithProgids").'QCaret.File') { throw "register-associations.cmd left QCaret.File out of $ext OpenWithProgids" }
}
Assert-Verb $classes $exe 'register-associations.cmd'
if (Test-Verb 'HKLM:') { throw 'register-associations.cmd wrote HKLM' }
Assert-DefaultVerbs 'register-associations.cmd' -Picker
Assert-CmdRuns 'register-associations.cmd'
Write-Output "PASS: register-associations.cmd in '$tree' added Open With and the Explorer menu verb under HKCU, QCaret as the default verb only of types with no program, and a .cmd still runs"

# The verb on a two-file selection through the shell's context menu: it starts qcaret.exe once
# per file, and each hands its file to the QCaret already running.
$logs = Join-Path $unpacked 'logs & more (2)'
New-Item -ItemType Directory -Path $logs | Out-Null
foreach ($name in @('first one.log', 'second & (two).log')) {
  Set-Content -LiteralPath (Join-Path $logs $name) -Value '2026-01-01 00:00:00 INFO mock line'
}
$log = Join-Path $tree 'logs\qcaret.log'
Start-Process -FilePath $exe -ArgumentList '-l' | Out-Null
try {
  Wait-Until { (Test-Path $log) -and (Get-Content -LiteralPath $log -Raw).Contains('Main window open') } "the zip's QCaret wrote no 'Main window open' to $log"
  $folder = (New-Object -ComObject Shell.Application).Namespace($logs)
  $names = @($folder.ParseName('first one.log').Verbs() | ForEach-Object { $_.Name -replace '&', '' })
  if ($names -notcontains 'Edit in QCaret') { throw "the context menu of a .log lacks Edit in QCaret: $($names -join ', ')" }
  $items = $folder.Items()
  # 0x40 SHCONTF_NONFOLDERS
  $items.Filter(0x40, '*.log')
  if ($items.Count -ne 2) { throw "the selection holds $($items.Count) files, expected 2" }
  $items.InvokeVerbEx('QCaret.Edit')
  Wait-Until {
    $handed = 0
    foreach ($match in [regex]::Matches((Get-Content -LiteralPath $log -Raw), 'Handoff of (\d+) files taken')) { $handed += [int] $match.Groups[1].Value }
    $handed -ge 2
  } "QCaret took no handoff of both selected files; log:`n$(Get-Content -LiteralPath $log -Raw)"
} finally {
  Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$tree\", [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Write-Output '--- zip QCaret log'
Write-Output (Get-Content -LiteralPath $log -Raw)
Write-Output '---'
Write-Output 'PASS: Edit in QCaret on two selected files handed both to the running QCaret'

Invoke-Register '/u' 'QCaret is out of the Open With lists and the Explorer right-click menu.'
foreach ($key in @('QCaret.File', 'Applications\qcaret.exe')) {
  if (Test-Path -LiteralPath "$classes\$key") { throw "register-associations.cmd /u left $classes\$key" }
}
foreach ($ext in $extensions) {
  $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids" -ErrorAction SilentlyContinue
  if ($progids -and $null -ne $progids.'QCaret.File') { throw "register-associations.cmd /u left QCaret.File in $ext OpenWithProgids" }
}
if (Test-Verb 'HKCU:') { throw "register-associations.cmd /u left HKCU:\$verbKey" }
Assert-NoNewStarKeys 'register-associations.cmd /u'
Assert-DefaultVerbs 'register-associations.cmd /u'
Write-Output 'PASS: register-associations.cmd /u removed Open With and the Explorer menu verb, left no new * or *\shell key, and restored the default verbs'
# The stopped QCaret's DLLs stay locked briefly after it exits.
Wait-Until { Remove-Item -Recurse -Force -LiteralPath $unpacked -ErrorAction SilentlyContinue; -not (Test-Path -LiteralPath $unpacked) } "cannot remove $unpacked"
