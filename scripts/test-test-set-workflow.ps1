$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
. ./scripts/test-set-tools.ps1
$root=Join-Path (Get-Location) ('target/modenv1-workflow-check-'+[guid]::NewGuid().ToString('N'))
$run=Join-Path $root 'runs/run-fixture'
New-Item -ItemType Directory -Path $run -Force|Out-Null
Copy-Item test-set/golden-set-modEnv1.json (Join-Path $run 'golden-set.json')
$records=@(@{turnId='Crafting-01';question='fixture only';sessionId='fixture-session';error=$false;elapsedMs=123;rule=@{passed=$true};metrics=@{httpRequestCount=0;llmCallCount=2;tokenIn=100;tokenOut=20};observation=@{resourceAccess=@{retries=0;redirects=0;waitMs=1;backoffMs=0}}},@{turnId='Entity-01';question='fixture only';sessionId='fixture-session';error=$false;elapsedMs=456;rule=@{passed=$false};metrics=@{httpRequestCount=1;llmCallCount=1;tokenIn=50;tokenOut=10}})
Write-TestText (Join-Path $run 'turns.jsonl') (($records|ForEach-Object {$_|ConvertTo-Json -Depth 10 -Compress}) -join "`n")
Write-TestText (Join-Path $run 'manifest.json') '{"status":"FIXTURE_ONLY"}'
New-TestReview $root $run
$review=Join-Path $root 'eval.md'
$original=[IO.File]::ReadAllText($review)
if($original -notmatch '(?m)^run_id: run-fixture$'){throw 'Broken run header'}
function Expect-Rejection($work){$rejected=$false;try{&$work}catch{$rejected=$true};if(-not $rejected){throw 'Expected submission rejection'};if(Test-Path (Join-Path $root 'result.md')){throw 'Rejected submission generated a result'}}
Expect-Rejection {Complete-TestReview $root}
Expect-Rejection {Complete-TestReview $root -ConfirmReviewComplete}
$graded=([regex]::new('人工评级：')).Replace($original,'人工评级：S',1)
$graded=([regex]::new('人工评级：(?!S)')).Replace($graded,'人工评级：B',1)
$graded=$graded.Replace('人工评级：S',"人工评级：`nS")
$graded=([regex]::new('<!-- human-comment:begin -->\n\n')).Replace($graded,"<!-- human-comment:begin -->`n保留自由文字 | **建议**`n",1)
Write-TestText $review ($graded.Replace('run-fixture','run-wrong'))
Expect-Rejection {Complete-TestReview $root -ConfirmReviewComplete}
Write-TestText $review $graded
$recordPath=Join-Path $run 'turns.jsonl'
$frozen=[IO.File]::ReadAllText($recordPath)
Write-TestText $recordPath ($frozen+' ')
Expect-Rejection {Complete-TestReview $root -ConfirmReviewComplete}
Write-TestText $recordPath $frozen
Complete-TestReview $root -ConfirmReviewComplete
$result=[IO.File]::ReadAllText((Join-Path $root 'result.md'))
foreach($needle in @('保留自由文字 | **建议**','P50 123','P95 456','输入Token 150','输出Token 30','未记录 59','| 1 | 0 | 1 | 0 | 0 |')){if(-not $result.Contains($needle)){throw ('Missing summary fact: '+$needle)}}
$hash=(Get-FileHash (Join-Path $root 'result.md')).Hash
$rejected=$false;try{Complete-TestReview $root -ConfirmReviewComplete}catch{$rejected=$true}
if(-not $rejected -or (Get-FileHash (Join-Path $root 'result.md')).Hash -ne $hash){throw 'Submitted review was overwritten'}
'Workflow checks passed: confirmation, blanks, run identity, grades, comments, metrics, missing turns and immutable submission. No API calls.'

} finally {Pop-Location}
