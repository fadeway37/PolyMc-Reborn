# SPDX-License-Identifier: LGPL-3.0-or-later
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$inputDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build\run\client-playtest-evidence-input'))
$evidenceDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build\playtest'))
$expectedInputDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build\run\client-playtest-evidence-input'))
$expectedEvidenceDirectory = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build\playtest'))
if ($inputDirectory -ne $expectedInputDirectory -or $evidenceDirectory -ne $expectedEvidenceDirectory) {
    throw 'Refusing to prepare playtest paths outside the fixed build directories'
}
foreach ($directory in @($inputDirectory, $evidenceDirectory)) {
    if (Test-Path -LiteralPath $directory) {
        Remove-Item -LiteralPath $directory -Recurse -Force
    }
}
New-Item -ItemType Directory -Path $inputDirectory -Force | Out-Null
$orchestratorLog = Join-Path $inputDirectory 'orchestrator.log'

function Write-OrchestratorLog {
    param([Parameter(Mandatory)][string]$Message)
    $line = '{0} {1}' -f [DateTime]::UtcNow.ToString('o'), $Message
    Add-Content -LiteralPath $orchestratorLog -Value $line -Encoding utf8
    Write-Host $Message
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function Test-TcpReadiness {
    param(
        [Parameter(Mandatory)][int]$Port,
        [Parameter(Mandatory)][System.Diagnostics.Process]$ServerProcess,
        [Parameter(Mandatory)][DateTime]$Deadline
    )
    while ([DateTime]::UtcNow -lt $Deadline) {
        $ServerProcess.Refresh()
        if ($ServerProcess.HasExited) {
            return $false
        }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $connect = $client.ConnectAsync([System.Net.IPAddress]::Loopback, $Port)
            if ($connect.Wait(1000) -and $client.Connected) {
                return $true
            }
        } catch {
            # A refused connection is expected while the server finishes binding.
        } finally {
            $client.Dispose()
        }
        Start-Sleep -Milliseconds 250
    }
    return $false
}

function Write-OrchestrationState {
    $passed = (
        $orchestrationFailures.Count -eq 0 -and
        $serverStarted -and $serverReadinessMarker -and $serverTcpReady -and
        $serverExitCode -eq 0 -and -not $serverTimedOut -and -not $serverForcedTermination -and
        $serverCleanStopRequested -and $serverCleanShutdown -and
        $clientStarted -and $clientExitCode -eq 0 -and
        -not $clientTimedOut -and -not $clientForcedTermination
    )
    $state = [ordered]@{
        schema_version = 1
        result = $(if ($passed) { 'passed' } else { 'failed' })
        failure_count = $orchestrationFailures.Count
        failures = @($orchestrationFailures)
        server = [ordered]@{
            started = $serverStarted
            readiness_marker = $serverReadinessMarker
            tcp_ready = $serverTcpReady
            exit_code = $serverExitCode
            timed_out = $serverTimedOut
            forced_termination = $serverForcedTermination
            clean_stop_requested = $serverCleanStopRequested
            clean_shutdown = $serverCleanShutdown
        }
        client = [ordered]@{
            started = $clientStarted
            exit_code = $clientExitCode
            timed_out = $clientTimedOut
            forced_termination = $clientForcedTermination
        }
    }
    $temporaryState = "$orchestrationStateFile.tmp"
    [System.IO.File]::WriteAllText(
        $temporaryState,
        (($state | ConvertTo-Json -Depth 5) + [Environment]::NewLine),
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryState -Destination $orchestrationStateFile -Force
}

function Invoke-EvidenceAggregator {
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if (-not $pythonCommand) {
        $pythonCommand = Get-Command python3 -ErrorAction SilentlyContinue
    }
    if (-not $pythonCommand) {
        Write-OrchestratorLog 'Python 3 was not found; evidence aggregation cannot run.'
        return 2
    }
    $aggregateOutput = & $pythonCommand.Source (Join-Path $PSScriptRoot 'aggregate_evidence.py') `
        '--project-root' $projectRoot `
        '--input-dir' $inputDirectory `
        '--output-dir' $evidenceDirectory `
        '--client-log' (Join-Path $projectRoot 'playtest\client-driver\build\run\client-playtest\logs\latest.log') `
        '--server-log' (Join-Path $projectRoot 'build\run\client-playtest-server\logs\latest.log') `
        '--orchestrator-log' $orchestratorLog `
        '--server-ready' $readyFile
    $exitCode = $LASTEXITCODE
    $aggregateOutput | ForEach-Object { Write-Host $_ }
    if (-not (Test-Path -LiteralPath (Join-Path $evidenceDirectory 'summary.json'))) {
        Write-OrchestratorLog 'Evidence aggregator did not create build/playtest/summary.json.'
        return 2
    }
    return [int]$exitCode
}

$serverRunDirectory = Join-Path $projectRoot 'build\run\client-playtest-server'
$readyFile = Join-Path $serverRunDirectory 'server-ready.json'
$stopFile = Join-Path $serverRunDirectory 'stop.request'
$orchestrationStateFile = Join-Path $inputDirectory 'orchestration-state.json'
$clientLog = Join-Path $projectRoot 'playtest\client-driver\build\run\client-playtest\logs\latest.log'
$serverLog = Join-Path $serverRunDirectory 'logs\latest.log'
foreach ($file in @($readyFile, $stopFile, $clientLog, $serverLog)) {
    Remove-Item -LiteralPath $file -Force -ErrorAction SilentlyContinue
}
$serverStdout = Join-Path $inputDirectory 'server-launcher.stdout.log'
$serverStderr = Join-Path $inputDirectory 'server-launcher.stderr.log'
$clientStdout = Join-Path $inputDirectory 'client-launcher.stdout.log'
$clientStderr = Join-Path $inputDirectory 'client-launcher.stderr.log'
$serverProcess = $null
$clientProcess = $null
$orchestrationFailures = [System.Collections.Generic.List[string]]::new()
$serverStarted = $false
$serverReadinessMarker = $false
$serverTcpReady = $false
$serverExitCode = $null
$serverTimedOut = $false
$serverForcedTermination = $false
$serverCleanStopRequested = $false
$serverCleanShutdown = $false
$clientStarted = $false
$clientExitCode = $null
$clientTimedOut = $false
$clientForcedTermination = $false
$serverPort = $null
$packPort = $null

try {
    $serverPort = Get-FreeTcpPort
    do { $packPort = Get-FreeTcpPort } while ($packPort -eq $serverPort)
    Write-OrchestratorLog "Allocated loopback server port $serverPort and resource-pack port $packPort."
    $serverArguments = @(
        '--no-daemon', '--console=plain',
        "-PplaytestServerPort=$serverPort",
        "-PplaytestPackPort=$packPort",
        "-PplaytestReportDir=$inputDirectory",
        'runProductionServerPlaytest'
    )
    $serverProcess = Start-Process -FilePath (Join-Path $projectRoot 'gradlew.bat') `
        -ArgumentList $serverArguments -WorkingDirectory $projectRoot -PassThru `
        -RedirectStandardOutput $serverStdout -RedirectStandardError $serverStderr -WindowStyle Hidden
    # Windows PowerShell loses ExitCode for redirected processes unless the native handle is opened first.
    $null = $serverProcess.Handle
    $serverStarted = $true
    Write-OrchestratorLog "Started independent production server process $($serverProcess.Id)."

    $deadline = [DateTime]::UtcNow.AddMinutes(6)
    while (-not (Test-Path -LiteralPath $readyFile)) {
        $serverProcess.Refresh()
        if ($serverProcess.HasExited) {
            $serverExitCode = [int]$serverProcess.ExitCode
            throw "Production server exited before readiness (exit $($serverProcess.ExitCode))"
        }
        if ([DateTime]::UtcNow -ge $deadline) {
            $serverTimedOut = $true
            throw 'Timed out waiting for the production server readiness marker'
        }
        Start-Sleep -Milliseconds 250
    }
    $ready = Get-Content -LiteralPath $readyFile -Raw | ConvertFrom-Json
    if ([int]$ready.server_port -ne $serverPort) {
        throw "Server readiness port $($ready.server_port) does not match requested port $serverPort"
    }
    $packSha256 = ([string]$ready.resource_pack_sha256).ToLowerInvariant()
    $packSha1 = ([string]$ready.resource_pack_sha1).ToLowerInvariant()
    if ($packSha256 -notmatch '^[0-9a-f]{64}$' -or $packSha1 -notmatch '^[0-9a-f]{40}$') {
        throw 'Server readiness marker contains malformed resource-pack hashes'
    }
    $serverReadinessMarker = $true
    Write-OrchestratorLog 'Production server readiness marker validated.'
    if (-not (Test-TcpReadiness -Port $serverPort -ServerProcess $serverProcess -Deadline ([DateTime]::UtcNow.AddSeconds(30)))) {
        $serverTimedOut = $true
        throw 'Timed out waiting for the production server TCP listener'
    }
    $serverTcpReady = $true
    Write-OrchestratorLog 'Production server TCP listener accepted a loopback probe.'

    $clientArguments = @(
        '--no-daemon', '--console=plain',
        "-PplaytestAddress=127.0.0.1:$serverPort",
        "-PplaytestReportDir=$inputDirectory",
        "-PplaytestPackSha256=$packSha256",
        "-PplaytestPackSha1=$packSha1",
        ':playtest:client-driver:runIsolatedProductionClientDriver'
    )
    $clientProcess = Start-Process -FilePath (Join-Path $projectRoot 'gradlew.bat') `
        -ArgumentList $clientArguments -WorkingDirectory $projectRoot -PassThru `
        -RedirectStandardOutput $clientStdout -RedirectStandardError $clientStderr -WindowStyle Hidden
    $null = $clientProcess.Handle
    $clientStarted = $true
    Write-OrchestratorLog "Started isolated production client process $($clientProcess.Id)."
    if (-not $clientProcess.WaitForExit(900000)) {
        $clientTimedOut = $true
        $clientForcedTermination = $true
        & taskkill.exe /PID $clientProcess.Id /T /F | Out-Null
        $null = $clientProcess.WaitForExit(30000)
        $clientProcess.Refresh()
        if ($clientProcess.HasExited) {
            $clientExitCode = [int]$clientProcess.ExitCode
        }
        throw 'Production client exceeded the 15-minute timeout'
    }
    $clientProcess.WaitForExit()
    $clientProcess.Refresh()
    $clientExitCode = [int]$clientProcess.ExitCode
    if ($clientProcess.ExitCode -ne 0) {
        throw "Production client driver exited with $($clientProcess.ExitCode)"
    }
    Write-OrchestratorLog 'Production client driver exited successfully.'
}
catch {
    $message = $_.Exception.Message
    $orchestrationFailures.Add($message)
    Write-OrchestratorLog "Orchestration failure: $message"
}
finally {
    try {
        if ($clientProcess -and -not $clientProcess.HasExited) {
            $clientForcedTermination = $true
            & taskkill.exe /PID $clientProcess.Id /T /F | Out-Null
            $null = $clientProcess.WaitForExit(30000)
            $message = 'Production client required forced cleanup after an orchestration failure'
            $orchestrationFailures.Add($message)
            Write-OrchestratorLog $message
        }
        if ($clientProcess) {
            $clientProcess.Refresh()
            if ($clientProcess.HasExited) {
                $clientExitCode = [int]$clientProcess.ExitCode
            }
        }
        if ($serverProcess -and -not $serverProcess.HasExited) {
            New-Item -ItemType File -Path $stopFile -Force | Out-Null
            $serverCleanStopRequested = $true
            Write-OrchestratorLog 'Requested a clean production server stop.'
            if (-not $serverProcess.WaitForExit(120000)) {
                $serverTimedOut = $true
                $serverForcedTermination = $true
                & taskkill.exe /PID $serverProcess.Id /T /F | Out-Null
                $orchestrationFailures.Add('Production server did not stop within 120 seconds')
                Write-OrchestratorLog 'Production server exceeded the stop timeout and was terminated.'
            } else {
                $serverProcess.WaitForExit()
            }
        }
        if ($serverProcess) {
            $serverProcess.Refresh()
            if ($serverProcess.HasExited) {
                $serverExitCode = [int]$serverProcess.ExitCode
            }
            $serverCleanShutdown = (
                $serverCleanStopRequested -and $serverProcess.HasExited -and
                $serverExitCode -eq 0 -and -not $serverForcedTermination
            )
            if ($serverProcess.HasExited -and $serverExitCode -ne 0) {
                $message = "Production server Gradle process exited with $($serverProcess.ExitCode)"
                $orchestrationFailures.Add($message)
                Write-OrchestratorLog $message
            }
        }
    } catch {
        $message = "Production server cleanup failed: $($_.Exception.Message)"
        $orchestrationFailures.Add($message)
        Write-OrchestratorLog $message
    }
}

Write-OrchestrationState
Write-OrchestratorLog 'Aggregating the strict whitelisted evidence bundle.'
$aggregateExitCode = Invoke-EvidenceAggregator
if ($orchestrationFailures.Count -gt 0 -or $aggregateExitCode -ne 0) {
    throw "Production client playtest failed; inspect build/playtest/summary.md and junit.xml. Orchestration failures: $($orchestrationFailures.Count)"
}
Write-OrchestratorLog 'Production client playtest and evidence validation passed.'
Write-Host 'Evidence bundle: build/playtest'
