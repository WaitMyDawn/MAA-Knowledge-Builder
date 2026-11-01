// 给每个 run 标注层级（full / directed / subset / probe / invalid）并写入 tier.json。
// 幂等：已有 tier.json 且层级一致时不重写。
const fs = require('fs');
const path = require('path');

const runsRoot = process.argv[2] || 'test-set/golden-set-modEnv1/runs';

// 显式标注优先：误配预算、仅连通性验证或人工指定的批次。
const OVERRIDES = {
    'run-20261101-174902-0b4f9c6e-p0p2-verify': {tier: 'invalid', validForTrend: false, note: '误配 40 次请求上限，预算耗尽导致多数轮失败'},
    'run-20261101-114122-1a3f8be9-modEnv1': {tier: 'full', validForTrend: true, note: 'v1 完整 61 题人工基线（规则口径 46/61）'}
};

function classify(turns) {
    if (turns >= 50) return {tier: 'full', validForTrend: true};
    if (turns >= 12) return {tier: 'directed', validForTrend: true};
    if (turns >= 6) return {tier: 'subset', validForTrend: true};
    return {tier: 'probe', validForTrend: false};
}

function turnCount(dir) {
    const file = path.join(runsRoot, dir, 'turns.jsonl');
    if (!fs.existsSync(file)) return 0;
    return fs.readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).length;
}

const result = [];
for (const entry of fs.readdirSync(runsRoot, {withFileTypes: true}).filter(d => d.isDirectory())) {
    const dir = entry.name;
    const turns = turnCount(dir);
    if (!turns) continue;
    const meta = OVERRIDES[dir] || classify(turns);
    const record = {runId: dir, tier: meta.tier, turnCount: turns, validForTrend: meta.validForTrend !== false, note: meta.note || ''};
    const file = path.join(runsRoot, dir, 'tier.json');
    let current = null;
    if (fs.existsSync(file)) {
        try { current = JSON.parse(fs.readFileSync(file, 'utf8')); } catch (error) { current = null; }
    }
    if (!current || current.tier !== record.tier || current.validForTrend !== record.validForTrend) {
        fs.writeFileSync(file, JSON.stringify({...record, classifiedAt: new Date().toISOString()}, null, 2));
    }
    result.push(record);
}

const byTier = new Map();
for (const record of result) {
    if (!byTier.has(record.tier)) byTier.set(record.tier, []);
    byTier.get(record.tier).push(record);
}
for (const [tier, rows] of [...byTier.entries()].sort()) {
    const trend = rows.filter(r => r.validForTrend).length;
    console.log(tier + ': ' + rows.length + ' 个运行（计入趋势 ' + trend + '）');
}
console.log('\n未计入趋势：');
for (const record of result.filter(r => !r.validForTrend)) console.log('- ' + record.runId + ' · ' + record.turnCount + ' 题 · ' + (record.note || '单点验证'));
