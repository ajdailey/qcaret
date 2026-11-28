# Runs scripts/container.bat (Windows, from cmd.exe as typed at a prompt) or scripts/container.sh
# (Linux) with QCARET_CONTAINER_DRYRUN=1, and fails unless each case prints the expected output
# directory and docker compose run line: named commands, --out with a space, the README's GTK 4
# route with its commas, -Dx=a,b, a quoted path with a space, and % ! ^ in arguments.
$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$work = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath().TrimEnd('\', '/') }
$out = Join-Path $work 'out dir'
$dist = Join-Path $repo 'dist'
$gtk4 = 'env SWT_GTK4=1 xvfb-run -a ./mvnw -B verify -pl qcaret-ui-swt,qcaret-launcher,qcaret-dist -am'
$failures = 0

function Assert-Output([string] $Case, [string] $Got, [string[]] $Want) {
  $lines = @($Got -split "`r?`n" | ForEach-Object { $_.TrimEnd() } | Where-Object { $_ -ne '' })
  if (($lines -join "`n") -cne ($Want -join "`n")) {
    Write-Output "FAIL: $Case printed`n$($lines -join "`n")`nexpected`n$($Want -join "`n")"
    $script:failures++
  } else {
    Write-Output "PASS: $Case"
  }
}

if ($IsWindows -or $env:OS -eq 'Windows_NT') {
  $bat = Join-Path $repo 'scripts\container.bat'
  $run = 'docker compose run --rm build'
  $check = 'xvfb-run -a -s "-screen 0 1280x1024x24" ./mvnw -B clean verify'
  $tail = "$gtk4 -Dx=a,b `"-Dpath=C:\a b`" `"-Dp=100%`" `"-Db=a!b`" `"-Dc=x^y`""
  # Each case: the argument text typed after container.bat, then the expected lines.
  $cases = @(
    @("--out `"$out`" $tail", @("QCARET_OUT=$out", "$run $tail")),
    @('check', @("QCARET_OUT=$dist", "$run $check")),
    @("--rebuild --out=`"$out`" build", @("QCARET_OUT=$out", $run)),
    @('', @("QCARET_OUT=$dist", $run))
  )
  foreach ($case in $cases) {
    $info = New-Object System.Diagnostics.ProcessStartInfo $env:ComSpec
    $info.Arguments = "/d /s /c `"`"$bat`" $($case[0])`""
    $info.UseShellExecute = $false
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.Environment['QCARET_CONTAINER_DRYRUN'] = '1'
    $process = [System.Diagnostics.Process]::Start($info)
    $process.StandardInput.Close()
    $stdout = $process.StandardOutput.ReadToEnd()
    if (-not $process.WaitForExit(60000)) { throw "container.bat $($case[0]) did not exit within 60 s" }
    if ($process.ExitCode -ne 0) { Write-Output "FAIL: container.bat $($case[0]) exited $($process.ExitCode)"; $failures++; continue }
    Assert-Output "container.bat $($case[0])" $stdout $case[1]
  }
} else {
  $sh = Join-Path $repo 'scripts/container.sh'
  $run = 'docker compose run --rm -T build'
  $env:QCARET_CONTAINER_DRYRUN = '1'
  $env:QCARET_CONTAINER_ENGINE = 'docker'
  $tailArgs = @($gtk4 -split ' ') + @('-Dx=a,b', '-Dpath=/tmp/a b', '-Dp=100%', '-Db=a!b', '-Dc=x^y')
  # printf %q quoting, as container.sh prints the line.
  $check = 'xvfb-run -a -s -screen\ 0\ 1280x1024x24 ./mvnw -B clean verify'
  $tail = '{0} -Dx=a\,b -Dpath=/tmp/a\ b -Dp=100% -Db=a\!b -Dc=x\^y' -f ($gtk4 -replace ',', '\,')
  $cases = @(
    @((@('--out', $out) + $tailArgs), @("QCARET_OUT=$out", "$run $tail")),
    @(@('check'), @("QCARET_OUT=$dist", "$run $check")),
    @(@('--rebuild', "--out=$out", 'build'), @("QCARET_OUT=$out", $run)),
    @(@(), @("QCARET_OUT=$dist", $run))
  )
  foreach ($case in $cases) {
    $stdout = (& bash $sh @($case[0]) | Out-String)
    if ($LASTEXITCODE -ne 0) { Write-Output "FAIL: container.sh $($case[0]) exited $LASTEXITCODE"; $failures++; continue }
    Assert-Output "container.sh $($case[0] -join ' ')" $stdout $case[1]
  }
}

if ($failures -gt 0) { throw "$failures container argument case(s) failed" }
