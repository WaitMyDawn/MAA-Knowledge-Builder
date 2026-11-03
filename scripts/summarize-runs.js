// 汇总 test-set 下所有运行归档，输出同口径对照表。
// 只读 turns.jsonl；不调用模型、不改任何归档。
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const runsRoot = process.argv[2] || 'test-set/golden-set-modEnv1/runs';
const runs = fs.readdirSync(runsRoot, {withFileTypes: true}).filter(d => d.isDirectory()).map(d => d.name).sort();

function readTurns(dir) {
    const file = path.join(runsRoot, dir, 'turns.jsonl');
    if (!fs.existsSync(file)) return [];
    return fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map(line => {
        try { return JSON.parse(line); } catch (error) { return null; }
    }).filter(Boolean);
}

function summarize(dir) {
    const turns = readTurns(dir);
    if (!turns.length) return null;
    let passed = 0, calls = 0, http = 0, tokensIn = 0, tokensOut = 0, elapsed = 0, budget = 0, modelErrors = 0;
    const failed = [];
    for (const turn of turns) {
        const metrics = turn.metrics || {};
        if (turn.rule && turn.rule.passed) passed++; else failed.push(turn.turnId);
        calls += metrics.llmCallCount || 0;
        http += metrics.httpRequestCount || 0;
        tokensIn += metrics.tokenIn || 0;
        tokensOut += metrics.tokenOut || 0;
        elapsed += turn.evalTotalMs || 0;
        const reason = turn.observation && turn.observation.toolLoop && turn.observation.toolLoop.reason;
        if (reason && reason.startsWith('MODEL_ERROR')) modelErrors++;
        if (!turn.rule || !turn.rule.hasAssertions) budget++;
    }
    return {dir, turns: turns.length, passed, failed, calls, http, tokensIn, tokensOut, elapsedMs: elapsed, modelErrors};
}

const rows = [];
for (const dir of runs) {
    const summary = summarize(dir);
    if (!summary) continue;
    summary.tier = 'unclassified'; summary.validForTrend = false;
    summary.questionVersion = 'unknown'; summary.questionHash = 'unknown';
    const frozenFile = path.join(runsRoot, dir, 'golden-set.json');
    if (fs.existsSync(frozenFile)) {
        const bytes = fs.readFileSync(frozenFile);
        summary.questionVersion = String(JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')).version || 'unknown');
        summary.questionHash = crypto.createHash('sha256').update(bytes).digest('hex');
    }
    const tierFile = path.join(runsRoot, dir, 'tier.json');
    if (fs.existsSync(tierFile)) {
        try { const meta = JSON.parse(fs.readFileSync(tierFile, 'utf8')); summary.tier = meta.tier || meta.tiers || 'unclassified'; summary.validForTrend = meta.validForTrend === true && ['full', 'directed', 'subset'].includes(summary.tier); } catch (error) {}
    }
    if (summary.questionHash === "unknown") summary.validForTrend = false;
    rows.push(summary);
}

console.log('| run | 题集版本 | 层级 | 计入趋势 | 题数 | 规则通过 | 失败题 | 模型请求 | HTTP | Token in/out | 逐轮累计秒 | MODEL_ERROR |');
console.log('|---|---|---|---|---:|---:|---|---:|---:|---|---:|---:|');
for (const row of rows) {
    console.log('| ' + row.dir + ' | v' + row.questionVersion + ' | ' + row.tier + ' | ' + (row.validForTrend ? '是' : '否') + ' | ' + row.turns + ' | ' + row.passed + ' (' + (row.passed / row.turns * 100).toFixed(1) + '%) | ' +
        (row.failed.join(',') || '-') + ' | ' + row.calls + ' | ' + row.http + ' | ' +
        row.tokensIn + '/' + row.tokensOut + ' | ' + (row.elapsedMs / 1000).toFixed(0) + 's | ' + row.modelErrors + ' |');
}

const byTurns = new Map();
for (const row of rows) {
    if (!row.validForTrend) continue;
    // 相同题数不证明题目及裁判相同；冻结题集摘要用于隔离跨版本结果。
    const key = row.questionVersion + ':' + row.turns + ':' + row.questionHash;
    if (!byTurns.has(key)) byTurns.set(key, []);
    byTurns.get(key).push(row);
}
console.log('\n## 冻结题集分组（环境、模型、会话顺序和缓存仍需另行核对）');
for (const [identity, group] of [...byTurns.entries()].sort((a, b) => b[1][0].turns - a[1][0].turns || a[0].localeCompare(b[0]))) {
    const rates = group.map(r => (r.passed / r.turns * 100).toFixed(1) + '%');
    console.log('- ' + group[0].turns + ' 题 / v' + group[0].questionVersion + ' / ' + group[0].questionHash.slice(0, 12) + '：' + group.length + ' 次运行，原始规则通过率 ' + rates.join(' / '));
}
