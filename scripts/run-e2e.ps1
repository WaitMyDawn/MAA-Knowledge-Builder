param(
    [Parameter(Mandatory = $true)][string]$ModsDirectory,
    [Parameter(Mandatory = $true)][string]$SnapshotDirectory,
    [string]$DataDirectory,
    [switch]$SkipBuild,
    [double]$FailUnder = 0
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    if (-not (Test-Path -LiteralPath $ModsDirectory -PathType Container)) { throw 'ModsDirectory does not exist.' }
    if (-not (Test-Path -LiteralPath (Join-Path $SnapshotDirectory 'snapshot.json') -PathType Leaf)) { throw 'SnapshotDirectory must contain a full MKB-Export snapshot.' }
    if (-not $DataDirectory) {
        $runSuffix = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + ([guid]::NewGuid().ToString('N').Substring(0, 8))
        $DataDirectory = Join-Path $projectRoot ('target/e2e-' + $runSuffix)
    }
    $DataDirectory = [System.IO.Path]::GetFullPath($DataDirectory)
    if (Test-Path -LiteralPath $DataDirectory) { throw 'From-zero validation requires a new, nonexistent DataDirectory.' }
    if (-not $SkipBuild) {
        & ./mvnw.cmd package -Prelease
        if ($LASTEXITCODE -ne 0) { throw 'Release build failed.' }
    }
    $arguments = @('-Djava.net.preferIPv6Addresses=system', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Xmx2g', '-cp', 'target/classes;target/lib/*',
        'yagen.waitmydawn.kb.HeadlessRunner', '--isolated', '--use-saved-key', '--data-dir', $DataDirectory,
        '--build', $ModsDirectory, '--build-documents', $SnapshotDirectory, '--bind-urls', '--test', 'test-questions.json', '--eval', 'test-set/old/golden-set.json',
        '--tag', 'from-zero', '--repeat', '1', '--judge')
    if ($FailUnder -gt 0) { $arguments += @('--fail-under', $FailUnder.ToString([System.Globalization.CultureInfo]::InvariantCulture)) }
    Write-Output ('Artifacts: ' + $DataDirectory)
    & java @arguments
    if ($LASTEXITCODE -ne 0) { throw ('E2E execution failed with exit code ' + $LASTEXITCODE) }
} finally { Pop-Location }
