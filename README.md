# MAA Knowledge Builder

**本地优先的 Minecraft 模组知识库构建与 Agentic RAG 图文问答工具**

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21.0.11-orange)](https://openjfx.io/)
[![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)](LICENSE.md)

MAA Knowledge Builder 是一款 Java 21 桌面应用：从你本地的 Minecraft 模组 JAR 构建**离线知识库**，用中文自然语言提问，AI 自动查配方、翻 Wiki、生成合成图。

---

## 目录

- [项目简介](#1-项目简介)
- [项目状态](#2-项目状态)
- [功能特性](#3-功能特性)
- [快速开始](#4-快速开始)
- [详细用法](#5-详细用法)
- [架构与数据流](#6-架构与数据流)
- [配置说明](#7-配置说明)
- [命令行与数据格式](#8-命令行与数据格式)
- [项目结构](#9-项目结构)
- [数据来源与合规](#10-数据来源与合规)
- [常见问题 FAQ](#11-常见问题-faq)
- [贡献指南](#12-贡献指南)
- [许可证](#13-许可证)
- [致谢与相关项目](#14-致谢与相关项目)

---

## 1. 项目简介

面向 Minecraft 模组玩家与整合包作者，提供：

- **本地优先的知识库构建**：扫描本地 JAR（Minecraft 本体 + NeoForge/Forge/Fabric 模组），自动提取纹理、配方、多语言文本并向量化，JAR 提取与向量化在本地完成；模组身份绑定和网页知识获取需要联网
- **Agentic RAG 中文问答**：规划、实体解析、正文检索与答案合成协作管道，自动推断物品注册名、按需增量抓取网页知识
- **图文混合渲染**：根据配方 JSON 动态生成合成图、熔炉图、锻造图、多方块图，以 Markdown 内联图片展示
- **种子分享**：导出/导入 `.maa-seed.json`，跨机器复现同一套知识库构建方案

## 2. 项目状态

当前处于**持续开发阶段**，桌面版优先支持 Windows 10/11，以中文问答为主。

## 3. 功能特性

- **本地知识库构建（离线可用）**：JAR 元数据解析 → 纹理/实体/配方/语言包提取 → 384 维语义向量入库；Modrinth API 精确绑定模组 slug；中文 Minecraft Wiki 镜像与 MC 百科按需抓取
- **Agentic RAG 中文问答**：合并规划器优先，保留分类回退；实体目录与别名检索 → 缓存优先的正文检索 → 答案合成与必要的覆盖检查；别名向量库 + 离散等级匹配解决“冰火传说/龙”类中文实体识别；多轮对话上下文记忆
- **图文混合渲染**：配方 JSON → 合成/熔炉/锻造/多方块图，最多渲染与问题相关的 4 个配方
- **全链路可观测**：每次问答的延迟、实体数、向量命中数、真实 Token 用量全程记账，指标按日持久化，日志落盘 `logs/yyyy-MM-dd-x.log`
- **种子导入/导出**：`.maa-seed.json` 记录模组 slug 与网页资源引用，可分享、可复现

## 4. 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| Maven | 3.8+（推荐使用内置 `mvnw`） |
| 操作系统 | Windows 10/11（macOS/Linux 需自行调整脚本） |
| JavaFX SDK | 仅打包需要（21.0.11） |

### 方式一：使用打包版本（推荐）

```bat
set JAVAFX_SDK=D:\path\to\javafx-sdk-21.0.11
.\package.bat
```

输出位于 `build\package\MAA-Knowledge-Builder\`，双击 `MAA-Knowledge-Builder.exe` 即可运行（自带捆绑 JRE）。

### 方式二：源码运行

```bash
git clone https://github.com/WaitMyDawn/MAA-Knowledge-Builder.git
cd MAA-Knowledge-Builder

.\mvnw javafx:run
```

Maven 的 `./mvnw.cmd package -Prelease` 另行生成 `target/MAA-Knowledge-Builder-1.0.0-SNAPSHOT-dist.zip`，包含应用 JAR 与依赖，不包含 JRE；运行需要 Java 21。此 ZIP 与上述 `package.bat` 的 Windows 应用目录是两种不同产物。

源码运行前，按文末“数据兼容与构建验证”安装与锁文件匹配的 ONNX 和词表资源。

### 最简运行三步

1. 启动后选择数据目录（所有知识库数据存储于此，自动初始化 H2 数据库与 ONNX 嵌入模型）
2. **Builder** 页选择模组文件夹 → `Scan JARs` → `Build Knowledge Base`
3. 切到 **Q&A** 页提问

## 5. 详细用法

### 5.1 GUI 工作流

| 页面 | 说明 |
|------|------|
| **Builder** | 选择模组文件夹 → Scan 解析 JAR 并绑定 Modrinth slug → Build 提取纹理/配方/语言包并构建向量库 |
| **Q&A** | 中文提问；支持“介绍一下它们”这类代词追问；命中配方时自动渲染合成/熔炉/锻造图 |
| **Seed** | 导出/导入 `.maa-seed.json` 训练种子 |
| **设置** | 跟随系统/浅色/深色主题、详情预取预算、数据目录、API Key、本地模型与作者署名 |
| **性能统计** | 查看每次问答的延迟、Token、命中数与历史指标 |
| **知识目录** | 按网站原分类浏览条目、打开/复制来源网址、查看本地正文及缓存时间；浏览条目不抓取网站 |
| **评测与复核** | 浏览规则判分、模型裁判、失败案例与人工复核报告 |

**注意**：Build 在临时库完成构建和校验后原子替换基础数据；失败或取消时保留原库，但不会影响 Q&A 阶段生成的会话增量库（`sessions/incremental/`）。

### 5.2 命令行快速上手

Windows 下通过 Maven exec 插件调用 `HeadlessRunner`：

```powershell
# 完整管道：清空 -> 构建 -> 回归测试
./mvnw.cmd exec:java "-Dexec.args=--data-dir D:/MKB --clean --build D:/mods --test"

# 仅用现有库跑问答测试
./mvnw.cmd exec:java "-Dexec.args=--data-dir D:/MKB --test test-questions.json"

# 网页抓取链路测试（抓取 -> 清洗 -> 分块 -> 入库）
./mvnw.cmd exec:java "-Dexec.args=--testweb D:/web-test"
```

### 5.3 自动化测试

- `--test`：读取 `test-questions.json`（多轮对话数组），每个对话模拟一个 GUI 会话（写 `sessions/*.json` 与会话增量库 `sessions/incremental/`，可被 GUI 加载回放），逐轮走完整 QA 管道并输出 Tier/命中/TF 指标
- `--eval`：读取 `golden-set.json`（黄金评测集 v8：标准答案要点 + 规则断言 + 裁判细则），做规则判分（主判）与 LLM 裁判（复核），产物隔离在 `{dataDir}/eval-runs/{runId}/`（turns.jsonl / summary.json / report.md / bad-cases.md / disagreements.md）
- `--compare <runA> <runB>`：对比两次评测的主指标与逐题回归/修复清单，用于版本消融表与 CI 门禁
- `--testweb`：读取 `test-web.json`（URL 列表），独立建库验证“网页抓取 → 正文清洗 → 分块 → 向量化”全链路

### 5.4 示例提问

- `龙有哪几种`
- `指南针怎么做`
- `冰火传说如何养龙`
- `介绍一下它们`（基于对话上下文的追问）
- `原版的信标怎么制作？`

## 6. 架构与数据流

```
┌─────────────────────────────────────────────────────────┐
│                    MAA Knowledge Builder                │
├─────────────────────────────────────────────────────────┤
│  UI Layer (JavaFX): 构建 / 问答 / 种子 / 设置 / 统计 / 目录 / 评测 │
├─────────────────────────────────────────────────────────┤
│  Agent Layer (agent/)                                   │
│  ├── ClassifyAgent ── 规划失败时回退分类                 │
│  ├── EntityAgent   ── 实体解析 + 别名多线路检索          │
│  ├── UrlAgent      ── 目录匹配与缓存优先的正文获取     │
│  └── AnswerAgent   ── Tier A/B/C/D 分层答案合成          │
├─────────────────────────────────────────────────────────┤
│  Service Layer (service/)                               │
│  ├── QaPipeline / KnowledgeBuilder / VectorStore        │
│  ├── EmbeddingService (ONNX 384-dim)                    │
│  ├── WikiScraperService (Wiki 镜像 + MC百科, 限流/退避)  │
│  └── RendererService / IncrementalKnowledgeService      │
├─────────────────────────────────────────────────────────┤
│  Data Layer (model/)                                    │
│  ├── DatabaseBuilder ── H2 DDL/CRUD                      │
│  ├── 基础库: rag_data.mv.db                              │
│  └── 增量库: sessions/incremental/rag_data_{sid}.mv.db  │
└─────────────────────────────────────────────────────────┘
```

### 数据流

```
用户提问
  → 合并规划器（问题意图、分类、实体候选；失败时分类回退）
  → EntityAgent（实体注册名推断 + 别名向量/字面检索）
  → UrlAgent（目录匹配 MC百科子网页 / Wiki 主题，优先复用公共缓存）
  → IncrementalKnowledgeService（按需抓取 → 分块 → 增量库）
  → VectorStore.searchAcross(base, inc)（双库向量检索）
  → Recipe SQL 精确查询 + 配方相关性评分
  → 制作/获取问题按需追溯一轮材料；缺少定向来源且候选超过 10 条时可选 LLM 重排
  → AnswerAgent（Tier 分层合成 Markdown，宽泛玩法按需检查覆盖）
  → RendererService（配方图渲染）→ UI 展示
```

## 7. 配置说明

### 7.1 全局设置文件

路径：`%USERPROFILE%/.maa_kb/settings.properties`

| 键 | 默认值 | 说明 |
|------|--------|------|
| `dataDir` | 首次启动选择 | 数据目录（数据库、纹理、生成图、日志） |
| `apiKey` | 空 | DeepSeek API Key |
| `embeddingModelName` | `all-MiniLM-L6-v2` | 嵌入模型 |
| `username` | `MAA_xxxxxx` | 会话标识（首次生成 UUID 前缀） |
| `defaultMcVersion` | `1.21.1` | 默认 MC 版本 |
| `defaultLoader` | `neoforge` | 默认模组加载器 |
| `themeMode` | `SYSTEM` | 跟随系统；也支持 `LIGHT`、`DARK` |
| `prewarmPages` | `6` | 每模组不同生物子分类的详情预取预算，0–30，0 关闭 |

### 7.2 爬虫安全参数（防限流 / 防 ban IP）

通过 JVM 系统属性（`-D...`）或环境变量配置，所有抓取统一经过限速、退避与缓存机制：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-Dmaa.scraper.minIntervalMs` | `800` | 每站点最小请求间隔（毫秒），最低 800，另加随机抖动 |
| `-Dmaa.scraper.maxRequestsPerHost` | `2000` | 进程内每站点请求预算，超限停止该站点请求 |
| `-Dmaa.scraper.proxy=host:port` | 无 | HTTP 代理连接配置；也可用环境变量 `MAA_SCRAPER_PROXY` |

内置防护：403/429 和反爬质询页面触发全实例共享站点冷却，遵循秒数或 HTTP 日期格式的 `Retry-After`；5xx 有界退避；公共正文缓存 7 天，进程内响应缓存 30 分钟；Cookie 会话复用；404 不重试。冷却期间使用已有有效缓存并跳过该站点网络访问，不绕过网站限制。

### 7.3 打包参数

| 参数 | 说明 |
|------|------|
| `JAVAFX_SDK` | JavaFX SDK 路径；未设置时依次探测 `D:\javafx-sdk-21.0.11`、`C:\javafx-sdk-21.0.11` |

### 7.4 数据目录布局

```
{dataDir}/
├── rag_data.mv.db                    # 基础向量库（H2）
├── textures/{modSlug}/...            # 提取的纹理
├── templates/                        # 原版 GUI 模板（裁剪缓存）
├── generated/{crafting|furnace|smithing}/*.png
├── jars/                             # 历史遗留（2026-09-07 起不再自动创建，无消费方）
├── wiki/                             # Wiki 相关缓存
├── sessions/
│   ├── {sessionId}.json              # 会话历史
│   └── incremental/rag_data_{sid}.mv.db  # 每会话独立增量库
└── logs/yyyy-MM-dd-x.log             # 文件日志
```

## 8. 命令行与数据格式

### 8.1 HeadlessRunner 命令

```text
Usage: HeadlessRunner --data-dir <path> [flags]

Required:
  --data-dir <path>     数据目录（数据库与资源）

Operations:
  --scan <mods-folder>  扫描 JAR 并绑定 Modrinth slug
  --build <mods-folder> 构建知识库（隐含 --scan）
  --clean               清理 generated/、logs/、sessions/
  --test [file]         运行自动化问答测试（默认 test-questions.json）
  --dialogue <4,5>      只运行测试文件中指定序号的对话（1 起始，逗号分隔，
                        例如 --dialogue 4,5 只跑第 4、5 个对话）
  --eval [file]         运行黄金评测集（默认 golden-set.json）：规则判分 + LLM 裁判，
                        产物隔离在 {dataDir}/eval-runs/{runId}/
  --tag <name>          评测 run 的标签（例如 --tag baseline）
  --repeat <n>          题级未指定时的默认重复次数（稳定性 pass@N）
  --judge/--no-judge    开/关 LLM 裁判（默认开启）
  --fail-under <rate>   评测通过率低于阈值时以退出码 2 结束（CI 门禁）
  --compare <a> <b>     对比两次评测 run（主指标 + 逐题回归/修复）
  --webdump <regex>     只读检查 rag_web_cache 的「实体→网页URL」映射（正则过滤），
                        加 --fetch 抓取匹配页面正文（限流+缓存），用于核对与构建参考答案
  --testweb <dir>       测试网页抓取/清洗/分块/入库链路（默认 test-web.json）
  --api-key <key>       指定本次运行的 DeepSeek API Key（临时，不写入配置；
                        省略时回退到 settings.properties 中已保存的 Key；
                        --test 在两者都缺失时不执行并输出原因）
```

### 8.2 `.maa-seed.json` 格式

训练种子仅包含**引用**（模组标识符与网页 URL），不包含版权内容：

```json
{
  "seedVersion": "1.0",
  "name": "知识库名称",
  "description": "描述",
  "author": "作者",
  "gameVersion": "1.21.1",
  "loader": "neoforge",
  "mods": [
    {
      "slug": "ice-and-fire",
      "modId": "iceandfire",
      "displayName": "冰火传说",
      "modVersion": "2.1.13",
      "mcVersion": "1.21.1",
      "loader": "neoforge",
      "source": "modrinth",
      "modrinthUrl": "https://modrinth.com/mod/..."
    }
  ],
  "webResources": [
    {
      "type": "mcmod_wiki",
      "url": "https://www.mcmod.cn/class/123.html",
      "description": "模组主页面"
    }
  ]
}
```

`webResources[].type` 取值：`mcmod_wiki` / `mod_wiki` / `custom`。

## 9. 项目结构

```
src/main/java/yagen/waitmydawn/kb/
├── MaaKnowledgeBuilderApp.java     # JavaFX 主入口（7 个页面）
├── HeadlessRunner.java             # CLI 入口（scan/build/test/testweb）
├── agent/                          # 四个 Agent
│   ├── ClassifyAgent.java          # LLM 问题分类
│   ├── EntityAgent.java            # 实体解析 + 别名多线路检索
│   ├── UrlAgent.java               # 子网页 URL 匹配 + 增量抓取
│   └── AnswerAgent.java            # Tier 分层答案合成
├── config/
│   ├── AppConfig.java              # 全局配置（settings.properties）
│   └── LogSetup.java               # 文件日志
├── dto/
│   ├── ClassificationResult.java
│   ├── QaMetrics.java
│   └── RetrievalResult.java
├── model/                          # H2 表实体与种子
│   ├── DatabaseBuilder.java / ModEntry.java / TrainingSeed.java
│   ├── RagItem / RagRecipe / RagMultiblock / RagWikiEntry / RagTextureCache
│   └── VanillaRecipeSeeder.java    # 原版配方预置
├── renderer/
│   ├── CraftingRenderer / FurnaceRenderer / SmithingRenderer
│   ├── BlockRenderer / MultiblockRenderer / ImageUtils
│   └── TemplateManager.java        # 原版 GUI 模板管理
├── service/
│   ├── QaPipeline / KnowledgeBuilder / VectorStore
│   ├── EmbeddingService + LocalEmbeddingModel + WordPieceTokenizer
│   ├── WikiScraperService          # Wiki 镜像 + MC百科抓取（限流/退避/缓存）
│   ├── IncrementalKnowledgeService + IncrementalDB + MultiDBManager
│   ├── JarScannerService / ModMetadataParser / ModrinthBinder
│   ├── TextureExtractor / EntityTextureScanner / RecipeExtractor / TextChunker
│   ├── RagAgentService / RendererService
│   ├── ChatHistoryService / MetricsHistoryService / AutoTestService
│   ├── SeedExportService / SeedImportService
│   └── 遗留: RetrieverService / RagDocumentService / EntityResolver / ClassifierService
└── ui/
    └── MarkdownRenderer.java       # Markdown → JavaFX TextFlow
```

## 10. 数据来源与合规

### 数据来源

| 来源 | 用途 | 许可/说明 |
|------|------|-----------|
| **all-MiniLM-L6-v2**（[Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)） | 中文/英文文本 → 384 维语义向量 | Apache License 2.0；项目内为 ONNX 格式（仅格式转换，权重不变） |
| **DeepSeek API**（deepseek-chat） | 分类/实体/答案合成推理 | 用户自备 API Key，费用由 DeepSeek 收取 |
| **中文 Minecraft Wiki 镜像**（`https://wiki.biligame.com/mc`） | Build 阶段抓取 16 个基础页面 | 内容 [CC BY-NC-SA 3.0](https://minecraft.wiki/w/Minecraft_Wiki:Copyrights)；官方站 zh.minecraft.wiki 被 Cloudflare 全站拦截，故改用同源镜像 |
| **MC 百科**（`https://www.mcmod.cn`） | 模组物品列表 + 子网页映射；Q&A 按需抓取 | 仅在分类页层级抓取，不访问单个物品子页面，频率受内置限速约束 |
| **Modrinth API v2** | Scan 阶段绑定模组 slug | [Modrinth API 免费公开使用](https://docs.modrinth.com/) |

### 合规与隐私

1. **Minecraft** 是 Mojang Studios / Microsoft 的注册商标。本项目是独立第三方工具，与 Mojang/Microsoft 无关联。
2. **模组 JAR**：仅在用户本地解析已合法获取的 JAR，提取纹理/配方元数据用于个人知识库，不修改、不重新分发。
3. **网页内容**：抓取的 MC 百科 / Wiki 镜像内容仅缓存在本地供个人查询，不对外分发或商业利用。
4. **训练种子**：只含模组标识符与网页 URL 引用，不含任何版权内容。
5. **隐私**：知识库、对话历史、纹理、日志全部存储在本地；API Key 以明文存放于 `%USERPROFILE%/.maa_kb/settings.properties`；LLM 调用会发送问题、近期会话上下文和检索片段（可能含来源 URL）；不要在问题或导入内容中放入敏感信息。
6. **使用限制**：仅限个人学习与研究；请遵守目标网站 robots.txt 与服务条款，勿用于大规模商业爬取。

## 11. 常见问题 FAQ

**Q：为什么用 wiki.biligame.com/mc 而不是官方 zh.minecraft.wiki？**
官方站被 Cloudflare 托管质询拦截，Java/Jsoup/.NET/curl 等非浏览器客户端一律 403（实测 MediaWiki API 与英文站同样被拦）。BWIKI 镜像内容同源、国内可直连，故全面改用镜像。

**Q：Build 卡住或抓取失败怎么排查？**
先看 `{dataDir}/logs/` 下当天的日志：限流退避、请求预算超限、反爬页识别都会有 WARN 记录。再检查网络连通性；必要时通过 `-Dmaa.scraper.proxy` 走代理。

**Q：没有 DeepSeek API Key 能正常使用吗？**
可以。问答会退化为离线检索结果展示（配方/向量命中内容），不调用 LLM。

**Q：Build 会清掉我的数据吗？**
Build 覆盖**基础库**，但 Q&A 阶段产生的**会话增量库**（`sessions/incremental/`）不受影响。

**Q：数据都存在哪里？**
全部在首次启动选择的数据目录内；全局设置（含 API Key）在 `%USERPROFILE%/.maa_kb/settings.properties`。

**Q：支持 macOS / Linux 吗？**
代码为跨平台 Java，但打包脚本与默认路径面向 Windows，其他平台需自行调整。

## 12. 许可证

```
Copyright (c) 2026 Yagen. All Rights Reserved.
```

> 本项目包含的第三方组件各自适用其原始许可证。

### 第三方组件许可证摘要

| 组件 | 许可证 |
|------|--------|
| all-MiniLM-L6-v2 (ONNX) | Apache 2.0 |
| JavaFX (OpenJFX) | GPL v2 + Classpath Exception |
| H2 Database | EPL 1.0 / MPL 2.0 |
| LangChain4j | Apache 2.0 |
| Jsoup | MIT |
| Jackson | Apache 2.0 |
| ONNX Runtime | MIT |
| SLF4J / Logback | MIT / EPL 1.0 |
| night-config | LGPL 3.0 |
| ControlsFX | BSD 3-Clause |

## 13. 致谢与相关项目

- **中文 Minecraft Wiki 镜像**（[wiki.biligame.com/mc](https://wiki.biligame.com/mc)）及其维护者
- **[MC 百科](https://www.mcmod.cn)**：模组中文资料库
- **[Modrinth](https://modrinth.com)**：模组元数据与 API
- **[DeepSeek](https://platform.deepseek.com/)**：大语言模型推理
- **[sentence-transformers / Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)**：嵌入模型
- **[JavaFX / OpenJFX](https://openjfx.io/)**、**[ControlsFX](https://github.com/controlsfx/controlsfx)**：桌面 UI
- **[LangChain4j](https://github.com/langchain4j/langchain4j)**：Agent / LLM 封装
- **[Jsoup](https://jsoup.org/)**、**[H2 Database](https://www.h2database.com/)**、**[ONNX Runtime](https://onnxruntime.ai/)**、**[Jackson](https://github.com/FasterXML/jackson)**、**[Logback](https://logback.qos.ch/)**、**[night-config](https://github.com/TheElectronWill/night-config)**

---

> *MAA Knowledge Builder — 让 AI 理解你的 Minecraft 世界。*


## 数据兼容与构建验证

向量索引包含模型文件、词表、分词方式和池化方式的指纹。ONNX 不可用时使用固定权重 `hash-ngram-v2`，不会在同一索引中混入回退向量。既有索引没有指纹，或指纹与当前模型不同，都需要重新构建基础知识库；旧会话仍可阅读，但继续检索须使用匹配模型或新建会话。选择新嵌入模型不会自动迁移历史向量。

构建先写临时 H2 库，读取大字段检查完成后，事务替换基础知识库的全部构建数据。空目录、无有效模组及发布异常均保留上一版基础库。纹理、模板与诊断映射按构建代次隔离，配方图片按配方内容与代次生成缓存名。失败构建留下的纹理代次可以在程序退出后清理；不要删除当前基础库引用的纹理目录。

数据目录变更在重启后生效。后台扫描、构建、问答串行执行，运行期间禁止切换或删除会话和数据库。退出时请求中断任务，网络请求仍可能等到超时返回。勾选其他会话数据库表示显式允许跨会话检索。

模型资源保持不纳入 Git（`src/main/resources/models/` 被忽略）。默认模型 all-MiniLM-L6-v2 的上游许可为 Apache-2.0，固定 ONNX 文件见 [官方来源](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/blob/main/onnx/model.onnx)。源码检出后需要安装资源，发布包可以携带模型及其第三方许可。发布者须取得与 `src/main/resources/embedding-model-lock.json` 一致的 ONNX 导出和词表，并执行：

```powershell
./scripts/install-embedding-model.ps1 -SourceDirectory <模型文件目录>
./mvnw.cmd test -Prelease
./mvnw.cmd package -Prelease
```

安装脚本先校验两个文件的 SHA-256 再复制；release 测试要求资源完整且匹配锁文件。不同 ONNX 导出即使来自同一模型名称也可能有不同摘要，不能直接替换锁文件而不验证推理行为。普通测试允许模型缺失，以便验证离线代码。

评测记录保存模型指纹、基础库内容摘要与代次、题集副本及摘要、源码与 POM 摘要。共享 HTTP 响应缓存不共享会话的增量库。LLM 请求数和 Token 来自模型监听器；提供方未返回用量时记录未知次数，不补造估算 Token。请求数不代表 SDK 内部网络重试次数。当前非流式接口没有首 Token 事件，TTFT 的 0 表示未观测；数据库查询计数仍为流程级估算，不宜当作精确 SQL 数量。

检索保留精确全表扫描，采用有界 Top-K 候选堆控制结果对象占用。是否需要近似向量索引、中文专用模型或异步网络并行，应依据真实库规模、延迟及固定题集结果决定。


## 从零建库与完整在线测试

```powershell
./scripts/run-e2e.ps1 -ModsDirectory D:/Minecraft/ModsForDatabase
```

脚本先运行 release 构建，再为每次执行创建新的 `target/e2e-*` 数据目录。顺序为：扫描及 Modrinth 身份绑定 → 从 JAR 和网页构建基础库 → `test-questions.json` 的完整多轮测试 → `golden-set.json` 的规则判分与在线裁判。会消耗已配置的 DeepSeek API Key 对应的额度；凭据仅用于当前进程，不输出、不写入测试目录。全局设置与 GUI 数据目录不改写。`-SkipBuild` 可复用已经编译好的程序，`-FailUnder 0.85` 可设置黄金题集通过率门槛。未设门槛时退出成功仅表示流水线执行完成，需查看质量报告。

也可直接启动 CLI，使用 `--isolated --use-saved-key --data-dir <新目录> --build <JAR目录> --test test-questions.json --eval golden-set.json --judge`。`--isolated` 默认不读取全局设置；`--use-saved-key` 明确授权借用已保存的 API Key 和嵌入模型名。普通多轮测试生成 `test-report-*.txt`、`test-metrics-*.csv`；黄金评测在 `eval-runs/<runId>/` 保存规则、裁判、坏例及输入指纹。

Java 网络启动策略使用 `-Djava.net.preferIPv6Addresses=system`，遵循操作系统的地址优先级。部分网络中 IPv4 与 IPv6 的连通性不同，Java 默认 IPv4 优先可能导致连接超时，而浏览器或 curl 正常。此选项不是代理，也不会修复不可达的网络；IDE、Maven `exec:java` 等已经启动的宿主 JVM 需在宿主启动参数中设置，修改源码后的进程需要重启。

Modrinth 绑定优先使用 JAR SHA-512 对应的版本 API，再解析项目 slug。摘要没有匹配才执行带模组类型、加载器和游戏版本过滤的精确版本搜索。`modrinth-no-match` 表示未找到精确匹配，`modrinth-unavailable` 表示连接、HTTP 或响应错误，不能推断项目不存在。后者短暂暂停同次扫描中的后续请求，并保留已有绑定，可恢复网络后重新扫描。

### 分类目录与枚举检索

构建保存 `rag_web_category` 的父子路径以及 `rag_web_category_member` 的成员归属；同一成员可以属于多个路径，技术性实体、合并子条目和未完成条目带有独立角色。原 `rag_web_cache.sub_web_page_json` 平面映射保持兼容。分类索引随基础库代次一起事务发布并计入内容摘要。旧库仍可进行实体检索，但枚举需要重新抓取分类后才能使用目录。

`ENUMERATE` 检索计划记录模组、对象类型、筛选条件、数量和完整清单意图。名称举例使用目录证据；驯服、骑乘等属性问题在目录内选择候选并抓取详情，默认最多核验 12 页（`-Dmaa.enumeration.maxPages`，硬上限 30）。回答必须披露未覆盖范围，不能将候选名称当成属性证明。实体检索保留当前操作诉求，按页面来源和模组约束正文；配方检索只保留目标产物，不使用同模组弱关联配方兜底。

黄金集 v8 包含关系匹配及实际列表成员计数规则。裁判接收本轮检索证据并区分核心答案缺失与正确补充；参考答案不构成封闭事实全集。报告中的检索命中和数据 Tier 表示检索数据可用性，不能单独证明答案正确或枚举完整。不同题集版本的通过率不能直接作为同口径提升幅度。

页面内排序结合向量相似度与操作关键词，分值归一到 0–1；它不等同于纯向量余弦相似度。主条目、名称待确认候选、机制支撑页及跨模组纠偏候选在检索上下文中标明不同用途。原版枚举继续使用 Wiki 正文和表格，不依赖模组分类目录。


### 界面主题与常用详情预取

设置提供“跟随系统 / 浅色 / 深色”，默认跟随系统应用配色，并即时更新主界面、弹窗和历史回答。Windows 上每两秒检查一次应用配色；支持的系统同步原生标题栏颜色，旧系统保留可用的原生窗口行为。

Build 默认每模组预取 6 个不同生物子分类的详情页，另补充已配置关系对应的目录页面。设置中可调整 0–30 页，0 关闭；CLI 的 `-Dmaa.build.prewarmPages=N` 优先于该设置。预取保留完整目录，并复用正文/向量缓存，不代表所有目录条目均已下载。

所有百科、Wiki 的真实网络请求（包括预取、搜索、分类页、重定向与重试）共享站点限速：默认至少 800ms 加 0–400ms 抖动；`maa.scraper.minIntervalMs` 可提高间隔，不能低于 800ms。MC 百科的主站和搜索子域共享限速与预算。收到 429 后至少冷却 30 秒，403 或识别人机验证页面后至少冷却 5 分钟；服务器 `Retry-After` 指定更长时间时遵循服务器要求，支持秒数和 HTTP 日期。冷却期间跳过该站点网络抓取，已有有效缓存仍可用。默认每站点进程内请求预算为 2000，可用 `maa.scraper.maxRequestsPerHost` 调整。
