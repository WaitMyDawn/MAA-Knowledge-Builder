# 评测报告 run-20261011-142538-53a61975-optimization-warm-repeat

- 评测集: golden-set.json (v7)
- tag: optimization-warm-repeat
- LLM 裁判: 开启
- 运行目录: D:\IdeaIU\project\MAA-Knowledge-Builder\target\e2e-optimization-20261011-v2\eval-runs\run-20261011-142538-53a61975-optimization-warm-repeat

## 总览

| 指标 | 值 |
|---|---|
| 总轮次 | 20 |
| 规则判分通过 | 19 |
| 通过率 | 95.0% |
| 平均规则得分 | 0.9722 |
| 检索命中率(Hit) | 100.0% |
| 平均耗时 | 4640 ms（P95 8715 ms）|
| 端到端耗时（含渲染+裁判） | 5751 ms（P95 9720 ms；渲染 2 ms / 裁判 1110 ms）|
| 平均 Token | in 5269 / out 861 |
| 平均答案长度 | 1341 字符 |
| 稳定性 pass@N | 90.0% |

Tier 分布: {TIER C=10, TIER B=4, TIER A=6}

## 网页访问与向量推理

计数覆盖轮次：20。旧版本未记录时为未知，不能解释成零请求。向量复用计数包括缓存载入与排序读取，可能多次读取同一块。

| 实际计数 | 合计 |
|---|---|
| httpRequestCount | 0 |
| httpErrorCount | 0 |
| httpResponseBytes | 0 |
| memoryPageCacheHits | 0 |
| persistentPageCacheHits | 102 |
| documentEmbeddingCount | 0 |
| queryEmbeddingCount | 24 |
| reusedEmbeddingCount | 3206 |
| embeddingTimeMs | 109 |

## 各阶段耗时

| 阶段 | 平均(ms) | P95(ms) | 最大(ms) | 占总时长 |
|---|---|---|---|---|
| classify | 0 | 0 | 0 | 0.0% |
| entity | 985 | 1859 | 1942 | 21.3% |
| url | 35 | 79 | 243 | 0.8% |
| vector | 19 | 34 | 88 | 0.4% |
| recipe | 1 | 3 | 3 | 0.0% |
| rerank | 0 | 0 | 0 | 0.0% |
| answer | 3613 | 6718 | 7095 | 77.9% |
| ttft | 0 | 0 | 0 | 0.0% |
| total | 4638 | 8710 | 9024 | - |

## LLM 裁判维度（复核）

| 指标 | 值 |
|---|---|
| 已判分轮次 | 20 |
| 裁判通过率 | 95.0% |
| 平均忠实度/完整性/正确性 | 4.9 / 4.7 / 4.85 |
| 幻觉率 | 0.0% |
| 与规则判分不一致 | 2 |

## 分类通过率

### 按题型 type

| 分组 | 总数 | 通过 | 通过率 |
|---|---|---|---|
| adversarial | 8 | 7 | 87.5% |
| basic | 6 | 6 | 100.0% |
| multi_step | 6 | 6 | 100.0% |

### 按类别 category

| 分组 | 总数 | 通过 | 通过率 |
|---|---|---|---|
| refusal | 8 | 7 | 87.5% |
| entity | 6 | 6 | 100.0% |
| mechanic | 4 | 4 | 100.0% |
| recipe | 2 | 2 | 100.0% |

### 按模组 modId

| 分组 | 总数 | 通过 | 通过率 |
|---|---|---|---|
| minecraft | 2 | 2 | 100.0% |
| iceandfire | 2 | 2 | 100.0% |
| alexsmobs | 6 | 6 | 100.0% |
| alexscaves | 4 | 3 | 75.0% |
| irons_spellbooks | 6 | 6 | 100.0% |

### 按对话

| 分组 | 总数 | 通过 | 通过率 |
|---|---|---|---|
| O-A02 | 2 | 2 | 100.0% |
| IAF-B01 | 2 | 2 | 100.0% |
| AM-B03 | 2 | 2 | 100.0% |
| AM-M01 | 2 | 2 | 100.0% |
| AM-A01 | 2 | 2 | 100.0% |
| AC-B01 | 2 | 2 | 100.0% |
| AC-A01 | 2 | 1 | 50.0% |
| IS-M02 | 2 | 2 | 100.0% |
| IS-M03 | 2 | 2 | 100.0% |
| IS-A01 | 2 | 2 | 100.0% |

## 稳定性（重复运行）

| Turn | Runs | Passes | pass@N |
|---|---|---|---|
| O-A02-T01 | 2 | 2 | ✅ |
| IAF-B01-T01 | 2 | 2 | ✅ |
| AM-B03-T01 | 2 | 2 | ✅ |
| AM-M01-T01 | 2 | 2 | ✅ |
| AM-A01-T01 | 2 | 2 | ✅ |
| AC-B01-T01 | 2 | 2 | ✅ |
| AC-A01-T01 | 2 | 1 | ❌ |
| IS-M02-T01 | 2 | 2 | ✅ |
| IS-M03-T01 | 2 | 2 | ✅ |
| IS-A01-T01 | 2 | 2 | ✅ |

## 失败题（bad cases）

### AC-A01-T01 — Alex's Caves 里的霸王龙（T-Rex）在哪里生成？怎么驯服？

- 规则得分: 0.6666666666666666
- 裁判: pass（回答明确指出无正式 T-Rex 条目，条件性介绍撼地龙并区分名称，生成与驯服信息均来自检索证据，未编造 alexscaves:t_rex。）
- 失败断言: [refusal(refused=false)]

