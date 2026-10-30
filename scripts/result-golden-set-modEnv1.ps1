param([switch]$ConfirmReviewComplete)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'test-set-tools.ps1')
$setDirectory=Join-Path $projectRoot 'test-set/golden-set-modEnv1'
if(-not(Test-Path -LiteralPath $setDirectory)){throw 'Run the question set before submitting a review.'}
$lock=$null
try {
    $lock=[IO.File]::Open((Join-Path $setDirectory '.manager.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    Complete-TestReview -SetDirectory $setDirectory -ConfirmReviewComplete:$ConfirmReviewComplete
} finally {if($null -ne $lock){$lock.Dispose()}}
