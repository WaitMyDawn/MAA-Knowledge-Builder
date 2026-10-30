param(
    [string]$DataDirectory='D:\Minecraft\MKB-Database',
    [switch]$UseSavedKey,
    [switch]$SkipCompile,
    [switch]$ArchivePreviousReview,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'test-set-tools.ps1')
Push-Location $projectRoot
$runLock=$null
try {
    $setFile=Join-Path $projectRoot 'test-set/golden-set-modEnv1.json'
    $set=Read-TestJson $setFile
    $turns=@($set.conversations|ForEach-Object {$_.turns})
    if($turns.Count -ne 61 -or $set.conversations.Count -ne 11){throw 'Unexpected modEnv1 specification size.'}
    if(@($turns|Group-Object id|Where-Object {$_.Count -gt 1}).Count){throw 'Duplicate turn ids.'}
    if(@($turns|Where-Object {$_.stabilityRuns -ne 1}).Count){throw 'This workflow requires exactly one execution per turn.'}
    if($ValidateOnly){Write-Output 'Specification valid: 61 turns, 11 conversations. No credentials loaded, no model calls.';return}
    if(-not $UseSavedKey){throw 'Use -UseSavedKey to explicitly authorize this run to read saved settings/API Key. No model judge is enabled.'}
    $DataDirectory=[IO.Path]::GetFullPath($DataDirectory)
    if(-not(Test-Path -LiteralPath (Join-Path $DataDirectory 'rag_data.mv.db'))){throw 'Existing modEnv1 base database required.'}
    $setDirectory=Join-Path $projectRoot 'test-set/golden-set-modEnv1'
    New-Item -ItemType Directory -Path $setDirectory -Force|Out-Null
    $runLock=[IO.File]::Open((Join-Path $setDirectory '.manager.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $reviewPath=Join-Path $setDirectory 'eval.md'
    if(Test-Path -LiteralPath $reviewPath){
        if(-not $ArchivePreviousReview){throw 'eval.md already exists. Submit it or explicitly use -ArchivePreviousReview to preserve it before a new run.'}
        $old=Read-TestJson (Join-Path $setDirectory 'active-run.json')
        if($old.runId -notmatch '^[A-Za-z0-9._-]+$' -or $old.runId -in @('.','..')){throw 'Invalid previous run id.'}
        $archive=Join-Path (Join-Path $setDirectory 'history') ($old.runId+'-before-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,6))
        New-Item -ItemType Directory -Path $archive|Out-Null
        Copy-Item -LiteralPath $reviewPath -Destination (Join-Path $archive 'eval.md')
        $oldResult=Join-Path $setDirectory 'result.md'
        if(Test-Path -LiteralPath $oldResult){
            $savedResult=Join-Path $archive 'result.md'
            Copy-Item -LiteralPath $oldResult -Destination $savedResult
            if((Get-FileHash -LiteralPath $oldResult).Hash -ne (Get-FileHash -LiteralPath $savedResult).Hash){throw 'Previous result archive verification failed.'}
            Remove-Item -LiteralPath $oldResult
        }
    }
    if(-not $SkipCompile){
        & ./mvnw.cmd -q '-DskipTests' compile dependency:copy-dependencies '-DincludeScope=runtime' '-DoutputDirectory=target/lib'
        if($LASTEXITCODE -ne 0){throw 'Compilation or runtime dependency preparation failed.'}
    }
    if(-not(Test-Path -LiteralPath target/classes/yagen/waitmydawn/kb/HeadlessRunner.class)){throw 'Compile the current sources before using -SkipCompile.'}
    $runId='run-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8)+'-modEnv1'
    $outputRoot=Join-Path $setDirectory 'sessions'
    New-Item -ItemType Directory -Path $outputRoot -Force|Out-Null
    $runDirectory=Join-Path $outputRoot $runId
    $started=[DateTimeOffset]::Now
    $sourceRows=@(Get-ChildItem src/main -Recurse -File|Sort-Object FullName|ForEach-Object {($_.FullName.Substring($projectRoot.Length+1).Replace('\','/'))+' '+(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash})
    $sha=[Security.Cryptography.SHA256]::Create()
    try{$sourceHash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes(($sourceRows -join "`n"))))).Replace('-','').ToLowerInvariant()}finally{$sha.Dispose()}
    $specHash=(Get-FileHash -LiteralPath $setFile -Algorithm SHA256).Hash.ToLowerInvariant()
    $javaArgs=@('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Xmx2g',('-Dmaa.eval.outputRoot='+$outputRoot),('-Dmaa.eval.runId='+$runId),'-cp','target/classes;target/lib/*','yagen.waitmydawn.kb.HeadlessRunner','--isolated','--use-saved-key','--data-dir',$DataDirectory,'--eval',$setFile,'--tag','modEnv1','--repeat','1','--no-judge')
    $exitCode=-1
    try {
        $ErrorActionPreference='Continue'
        & java @javaArgs
        $exitCode=$LASTEXITCODE
    } finally {
        $ErrorActionPreference='Stop'
        if(Test-Path -LiteralPath $runDirectory){
            $artifactErrors=New-Object System.Collections.ArrayList
            $rows=@()
            if(Test-Path -LiteralPath (Join-Path $runDirectory 'turns.jsonl')){
                $rows=@(Read-TestTurns $runDirectory)
                foreach($record in $rows){
                    $session=Get-TestValue $record 'sessionId' (Get-TestValue $record.metrics 'sessionId' '')
                    if([string]::IsNullOrWhiteSpace($session) -or -not(Test-Path -LiteralPath (Join-Path (Join-Path $runDirectory 'sessions') ($session+'.json')))){[void]$artifactErrors.Add('Missing session: '+$record.turnId)}
                    foreach($failure in @(Get-TestValue $record 'assetArchiveErrors' @())){if(-not [string]::IsNullOrWhiteSpace([string]$failure)){[void]$artifactErrors.Add($record.turnId+': '+$failure)}}
                }
            }
            $status=if($exitCode -eq 0 -and $rows.Count -eq 61 -and $artifactErrors.Count -eq 0){'COMPLETED'}else{'INCOMPLETE'}
            $configSnapshot=if(Test-Path -LiteralPath (Join-Path $runDirectory 'config.json')){Read-TestJson (Join-Path $runDirectory 'config.json')}else{$null}
            $runtimeSnapshot=if($rows.Count){Get-TestValue $rows[0].observation 'runtimeEvidence'}else{$null}
            $manifest=[ordered]@{evaluationConfig=$configSnapshot;runtimeSnapshot=$runtimeSnapshot;runId=$runId;status=$status;startedAt=$started.ToString('o');finishedAt=[DateTimeOffset]::Now.ToString('o');wallClockMs=([DateTimeOffset]::Now-$started).TotalMilliseconds;exitCode=$exitCode;sourceSha256=$sourceHash;questionSetSha256=$specHash;dataDirectory=$DataDirectory;plannedTurns=61;recordedTurns=$rows.Count;conversations=11;modelJudge=$false;repeat=1;cacheState='EXISTING_BASE_AND_SHARED_PUBLIC_CACHE_NOT_RESET';artifactErrors=@($artifactErrors)}
            Write-TestText (Join-Path $runDirectory 'manifest.json') ($manifest|ConvertTo-Json -Depth 12)
            if($rows.Count){New-TestReview -SetDirectory $setDirectory -RunDirectory $runDirectory}
            Write-Output ('Run archive: '+$runDirectory)
            Write-Output ('Human evaluation: '+(Join-Path $setDirectory 'eval.md'))
        }
    }
    if($exitCode -ne 0){throw ('Question execution failed with exit code '+$exitCode+'; any partial records and review template were preserved.')}
    Write-Output 'Review eval.md and import the run archive in the application. No result.md for this run has been generated.'
} finally {
    if($null -ne $runLock){$runLock.Dispose()}
    Pop-Location
}
