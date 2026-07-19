# SPDX-License-Identifier: LGPL-3.0-or-later
$ErrorActionPreference = 'Stop'
$projectRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$evidence = Join-Path $projectRoot 'build\reproducibility'

function Build-Hashes([string] $name) {
    # Windows PowerShell turns native stderr into error records when it is
    # merged into a captured stream. Gradle legitimately emits compiler
    # warnings there, so judge the nested build only by its process exit code.
    $previousErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & (Join-Path $projectRoot 'gradlew.bat') clean jar sourcesJar javadocJar `
        :api:jar :api:sourcesJar :api:javadocJar --no-daemon | Out-Host
    $gradleExitCode = $LASTEXITCODE
    $ErrorActionPreference = $previousErrorAction
    if ($gradleExitCode -ne 0) { throw "Archive build $name failed with exit code $gradleExitCode" }
    $archives = @(
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'build\libs') -File -Filter 'polymc-reborn-*.jar'
        Get-ChildItem -LiteralPath (Join-Path $projectRoot 'api\build\libs') -File -Filter 'polymc-reborn-api-*.jar'
    ) | Sort-Object Name
    if ($archives.Count -ne 6) { throw "Archive build $name produced $($archives.Count), expected 6" }
    $result = [ordered]@{}
    foreach ($archive in $archives) {
        $result[$archive.Name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive.FullName).Hash.ToLowerInvariant()
    }
    return $result
}

$first = Build-Hashes 'first'
$second = Build-Hashes 'second'
if (($first | ConvertTo-Json -Compress) -ne ($second | ConvertTo-Json -Compress)) {
    throw "Archive hashes differ between clean builds: first=$($first | ConvertTo-Json -Compress) second=$($second | ConvertTo-Json -Compress)"
}
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$json = [ordered]@{ schema_version = 1; result = 'passed'; archive_count = 6; first = $first; second = $second } |
    ConvertTo-Json -Depth 4
$utf8WithoutBom = [System.Text.UTF8Encoding]::new($false)
[System.IO.File]::WriteAllText((Join-Path $evidence 'archives.json'),
    ($json -replace "`r`n", "`n") + "`n", $utf8WithoutBom)
