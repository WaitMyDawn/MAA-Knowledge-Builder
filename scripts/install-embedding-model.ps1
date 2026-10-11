param(
    [Parameter(Mandatory = $true)][string]$SourceDirectory,
    [string]$DestinationDirectory = (Join-Path $PSScriptRoot '..\src\main\resources\models\all-MiniLM-L6-v2')
)
$ErrorActionPreference = 'Stop'
$modelLock = Get-Content -LiteralPath (Join-Path $PSScriptRoot '..\src\main\resources\embedding-model-lock.json') -Raw | ConvertFrom-Json
foreach ($asset in $modelLock.files.PSObject.Properties) {
    $sourceFile = Join-Path $SourceDirectory $asset.Name
    $actualHash = (Get-FileHash -LiteralPath $sourceFile -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $asset.Value) { throw "Model checksum mismatch: $($asset.Name)" }
}
New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
foreach ($asset in $modelLock.files.PSObject.Properties) {
    Copy-Item -LiteralPath (Join-Path $SourceDirectory $asset.Name) -Destination (Join-Path $DestinationDirectory $asset.Name) -Force
}
Write-Output 'Verified embedding model installed.'
