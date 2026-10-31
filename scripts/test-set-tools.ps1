$ErrorActionPreference = 'Stop'
function Write-TestText([string]$Path,[string]$Text) {
    [IO.File]::WriteAllText($Path,$Text,(New-Object Text.UTF8Encoding($false)))
}
function Read-TestJson([string]$Path) { return ([IO.File]::ReadAllText($Path) | ConvertFrom-Json) }
function Read-TestTurns([string]$RunDirectory) {
    $path=Join-Path $RunDirectory 'turns.jsonl'
    if(-not(Test-Path -LiteralPath $path)){throw 'No turns.jsonl; no completed questions are available.'}
    $rows=@([IO.File]::ReadAllLines($path) | Where-Object {-not [string]::IsNullOrWhiteSpace($_)} | ForEach-Object {$_ | ConvertFrom-Json})
    if(-not $rows.Count){throw 'Empty question records.'}
    $duplicates=@($rows | Group-Object turnId | Where-Object {$_.Count -gt 1})
    if($duplicates.Count){throw 'Repeated turn ids require a separate stability evaluation workflow.'}
    return $rows
}
function Get-TestValue($Object,[string]$Name,$Default=$null) {
    if($null -eq $Object){return $Default}
    $property=$Object.PSObject.Properties[$Name]
    if($null -eq $property -or $null -eq $property.Value){return $Default}
    return $property.Value
}
function New-TestReview([string]$SetDirectory,[string]$RunDirectory) {
    $records=@(Read-TestTurns $RunDirectory)
    $runId=Split-Path $RunDirectory -Leaf
    $lines=@('# modEnv1 人工评估','',('run_id: '+$runId),'','每轮在“人工评级”填写 S/A/B/C/D；S 可以不填写评语。空白表示未审阅。','所有自由评语保留在标记之间。全部审阅后使用汇总脚本的 -ConfirmReviewComplete；该确认之前不生成本轮 result.md。','','## 总体意见','','<!-- general-comment:begin -->','','<!-- general-comment:end -->','')
    foreach($record in $records){
        $pass=if($record.rule.passed){'PASS'}else{'FAIL'}
        $failures=@(Get-TestValue $record.rule 'outcomes' @() | Where-Object {$null -ne $_ -and -not $_.passed} | ForEach-Object {$_.kind+': '+$_.detail})
        $failureText=if($failures.Count){$failures -join '；'}else{'无已记录的失败细项；仍需人工评审'}
        $session=Get-TestValue $record 'sessionId' (Get-TestValue $record.metrics 'sessionId' '')
        $lines+=@(('## '+$record.turnId),'',('问题：'+$record.question),'',('规则结果（仅供参考）：'+$pass),('失败细项：'+$failureText),('会话：runs/'+$runId+'/sessions/'+$session+'.json'),'','人工评级：','','<!-- human-comment:begin -->','','<!-- human-comment:end -->','')
    }
    Write-TestText (Join-Path $SetDirectory 'eval.md') ($lines -join "`n")
    Write-TestText (Join-Path $SetDirectory 'active-run.json') (@{runId=$runId;runDirectory=$RunDirectory;turnRecordsSha256=(Get-FileHash -LiteralPath (Join-Path $RunDirectory 'turns.jsonl') -Algorithm SHA256).Hash.ToLowerInvariant();questionSetSha256=(Get-FileHash -LiteralPath (Join-Path $RunDirectory 'golden-set.json') -Algorithm SHA256).Hash.ToLowerInvariant()} | ConvertTo-Json)
}
function Complete-TestReview([string]$SetDirectory,[switch]$ConfirmReviewComplete) {
    if(-not $ConfirmReviewComplete){throw 'Explicit -ConfirmReviewComplete is required; editing eval.md is not submission.'}
    $active=Read-TestJson (Join-Path $SetDirectory 'active-run.json')
    if($active.runId -notmatch '^[A-Za-z0-9._-]+$' -or $active.runId -in @('.','..')){throw 'Invalid active run id.'}
    $runDirectory=[IO.Path]::GetFullPath($active.runDirectory)
    $expected=[IO.Path]::GetFullPath((Join-Path (Join-Path $SetDirectory 'runs') $active.runId))
    if($runDirectory -ne $expected){throw 'Run directory does not match active run.'}
    $specPath=Join-Path $runDirectory 'golden-set.json'
    if((Get-FileHash -LiteralPath $specPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $active.questionSetSha256){throw 'Run question snapshot changed.'}
    if((Get-FileHash -LiteralPath (Join-Path $runDirectory 'turns.jsonl') -Algorithm SHA256).Hash.ToLowerInvariant() -ne $active.turnRecordsSha256){throw 'Original turn records changed.'}
    $records=@(Read-TestTurns $runDirectory)
    $spec=Read-TestJson $specPath
    $planned=@($spec.conversations | ForEach-Object {$_.turns})
    $known=@{};foreach($turn in $planned){$known[$turn.id]=$true}
    foreach($record in $records){if(-not $known.ContainsKey([string]$record.turnId)){throw ('Unknown recorded question '+$record.turnId)}}
    $reviewPath=Join-Path $SetDirectory 'eval.md'
    $text=[IO.File]::ReadAllText($reviewPath)
    if($text -notmatch ('(?m)^run_id:\s*'+[regex]::Escape($active.runId)+'\s*$')){throw 'eval.md belongs to a different run.'}
    $pattern='(?ms)^## (?<id>(?:Crafting|Entity|Gameplay|Webpage|Error|MultiStep)-\d{2})[ \t]*\r?\n(?<body>.*?)(?=^## (?:Crafting|Entity|Gameplay|Webpage|Error|MultiStep)-\d{2}[ \t]*\r?$|\z)'
    $blocks=[regex]::Matches($text,$pattern)
    $reviews=@{}
    foreach($block in $blocks){
        $id=$block.Groups['id'].Value
        if($reviews.ContainsKey($id)){throw ('Duplicate review '+$id)}
        $body=$block.Groups['body'].Value
        $comment=[regex]::Match($body,'(?s)<!-- human-comment:begin -->(.*?)<!-- human-comment:end -->')
        if(-not $comment.Success){throw ('Missing comment markers '+$id)}
        $header=$body.Substring(0,$comment.Index)
        $grades=[regex]::Matches($header,'(?m)^人工评级[：:][ \t]*(?:\r?\n[ \t]*)?([SABCD])[ \t]*\r?$')
        if($grades.Count -ne 1){throw ('Missing or ambiguous S/A/B/C/D grade: '+$id)}
        $reviews[$id]=@{grade=$grades[0].Groups[1].Value;comment=$comment.Groups[1].Value.Trim()}
    }
    if($reviews.Count -ne $records.Count){throw 'Review count does not match executed records.'}
    foreach($record in $records){if(-not $reviews.ContainsKey([string]$record.turnId)){throw ('Unreviewed question '+$record.turnId)}}
    $manifestPath=Join-Path $runDirectory 'manifest.json'
    $manifest=if(Test-Path -LiteralPath $manifestPath){Read-TestJson $manifestPath}else{@{status='UNKNOWN'}}
    $gradeCounts=@{S=0;A=0;B=0;C=0;D=0};foreach($review in $reviews.Values){$gradeCounts[$review.grade]++}
    $rulePass=@($records|Where-Object {$_.rule.passed}).Count
    $errors=@($records|Where-Object {$_.error}).Count
    $missing=@($planned|Where-Object {$_.id -notin $records.turnId})
    $times=@($records|ForEach-Object {[double]$_.elapsedMs}|Sort-Object)
    $p50=$times[[Math]::Max(0,[Math]::Ceiling($times.Count*0.50)-1)]
    $p95=$times[[Math]::Max(0,[Math]::Ceiling($times.Count*0.95)-1)]
    $http=0L;$calls=0L;$inputTokens=0L;$outputTokens=0L;$retry=0L;$redirect=0L;$wait=0L;$backoff=0L;$resourceRows=0;$unknownUsageCalls=0L
    foreach($record in $records){
        $m=$record.metrics
        $http+=[long](Get-TestValue $m 'httpRequestCount' 0)
        $calls+=[long](Get-TestValue $m 'llmCallCount' 0)
        $inputTokens+=[long](Get-TestValue $m 'tokenIn' 0)
        $outputTokens+=[long](Get-TestValue $m 'tokenOut' 0)
        $unknownUsageCalls+=[long](Get-TestValue $m 'unknownUsageCalls' 0)
        $resource=Get-TestValue $record.observation 'resourceAccess'
        if($null -ne $resource){$resourceRows++;$retry+=[long]$resource.retries;$redirect+=[long]$resource.redirects;$wait+=[long]$resource.waitMs;$backoff+=[long]$resource.backoffMs}
    }
    $usable=[Math]::Round(100.0*($gradeCounts.S+$gradeCounts.A)/$records.Count,2)
    $lines=@('# modEnv1 测试结果','',('run_id: '+$active.runId),('人工提交时间：'+[DateTimeOffset]::Now.ToString('o')),'','## 本轮身份与可比条件','',('题集 SHA-256：`'+$active.questionSetSha256+'`'),('原始归档：`runs/'+$active.runId+'`'),('执行状态：'+$manifest.status),'模型裁判：关闭。人工评级与自动规则独立统计。','', '执行 manifest：','', '```json',($manifest|ConvertTo-Json -Depth 12),'```','','## 执行情况','',('计划 '+$planned.Count+' 轮，已记录 '+$records.Count+'，错误 '+$errors+'，未记录 '+$missing.Count+'。'),('未记录题号：'+($missing.id -join '、')),'未记录与前置条件缺失不计为通过；不能因脚本退出正常就宣称题集通过。','','## 规则裁判','',('PASS '+$rulePass+' / '+$records.Count+'；FAIL '+($records.Count-$rulePass)+'。'),'规则通过仅证明已实现的检查项，不证明所有获取途径、属性、战术或图片像素语义正确。','','## 人工裁判','',('| S | A | B | C | D |'),('| ---: | ---: | ---: | ---: | ---: |'),('| '+$gradeCounts.S+' | '+$gradeCounts.A+' | '+$gradeCounts.B+' | '+$gradeCounts.C+' | '+$gradeCounts.D+' |'),('已执行题评审覆盖率：100%；相对计划题评审覆盖率：'+[Math]::Round(100.0*$records.Count/$planned.Count,2)+'%。'),('人工可用比例（S+A／已评审）：'+$usable+'%。'),'','## 逐题对照表','','| 题号 | 规则 | 人工 | 问答毫秒 | 证据 |','| --- | --- | --- | ---: | --- |')
    foreach($record in $records){$pass=if($record.rule.passed){'PASS'}else{'FAIL'};$lines+='| '+$record.turnId+' | '+$pass+' | '+$reviews[$record.turnId].grade+' | '+$record.elapsedMs+' | `runs/'+$active.runId+'/turns.jsonl` |'}
    $lines+=@('','## 人工评语原文','')
    $general=[regex]::Match($text,'(?s)<!-- general-comment:begin -->(.*?)<!-- general-comment:end -->')
    if($general.Success){$lines+=@('### 总体意见','',$general.Groups[1].Value.Trim(),'')}
    foreach($record in $records){$lines+=@(('### '+$record.turnId+' — '+$reviews[$record.turnId].grade),'',$reviews[$record.turnId].comment,'')}
    $lines+=@('## 性能','',('纯问答累计 '+($times|Measure-Object -Sum).Sum+' ms；P50 '+$p50+' ms；P95 '+$p95+' ms。'),'累计问答时间不含编译、进程初始化、全部归档与人工评审，不等同脚本墙钟。',('已记录资源 HTTP '+$http+'；模型调用 '+$calls+'；输入Token '+$inputTokens+'；输出Token '+$outputTokens+'。'),('网络细分记录覆盖 '+$resourceRows+'/'+$records.Count+' 轮；重试 '+$retry+'、重定向 '+$redirect+'、限流等待 '+$wait+' ms、退避 '+$backoff+' ms。'),('未报告完整Token用量的模型调用：'+$unknownUsageCalls+'；未记录的指标不得解释为已经测得0。'),'','## 双线分歧（待 Agent 分析）','')
    foreach($record in $records){$grade=$reviews[$record.turnId].grade;if(($record.rule.passed -and $grade -in @('C','D')) -or (-not $record.rule.passed -and $grade -in @('S','A'))){$lines+='- '+$record.turnId+'：规则='+$record.rule.passed+'，人工='+$grade+'。'}}
    $lines+=@('','以上仅机械列出明显分歧，Agent 需结合全部评语和规则细项进一步分析，不能据评级自动归因。','','## 与上版比较（待 Agent 完善）','','需核对题目、规则、代码、环境、资料、模型和缓存条件；不能直接跨口径比较百分比。','','## 后续问题清单和改进方案（待 Agent 完善）','','根据人工原文和原始会话列出问题、证据、优先级、预计修复与回归题号；不得擅自修改人工评级。')
    $result=$lines -join "`n"
    $history=Join-Path (Join-Path $SetDirectory 'history') $active.runId
    New-Item -ItemType Directory -Path $history -Force|Out-Null
    $submitted=Join-Path $history 'eval.md'
    if(Test-Path -LiteralPath $submitted){throw 'This run already has a submitted evaluation; preserve it and create an explicit revision.'}
    Copy-Item -LiteralPath $reviewPath -Destination $submitted
    Write-TestText (Join-Path $history 'result.md') $result
    Write-TestText (Join-Path $SetDirectory 'result.md') $result
    Write-TestText (Join-Path $history 'human-review.json') ($reviews|ConvertTo-Json -Depth 8)
    Write-Output ('Result: '+(Join-Path $SetDirectory 'result.md'))
}
