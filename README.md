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

- **离线知识库构建**：扫描本地 JAR（Minecraft 本体 + NeoForge/Forge/Fabric 模组），自动提取纹理、配方、多语言文本并向量化，全程本地完成
- **Agentic RAG 中文问答**：四 Agent 协作管道（分类 → 实体解析 → URL 匹配 → 答案合成），自动推断物品注册名、按需增量抓取网页知识
- **图文混合渲染**：根据配方 JSON 动态生成合成图、熔炉图、锻造图、多方块图，以 Markdown 内联图片展示
- **种子分享**：导出/导入 `.maa-seed.json`，跨机器复现同一套知识库构建方案

## 2. 项目状态

当前处于**持续开发阶段**，桌面版优先支持 Windows 10/11，以中文问答为主。

## 3. 功能特性

- **本地知识库构建（离线可用）**：JAR 元数据解析 → 纹理/实体/配方/语言包提取 → 384 维语义向量入库；Modrinth API 精确绑定模组 slug；中文 Minecraft Wiki 镜像与 MC 百科按需抓取
- **Agentic RAG 中文问答**：ClassifyAgent → EntityAgent → UrlAgent → AnswerAgent 四步管道；别名向量库 + 离散等级匹配解决“冰火传说/龙”类中文实体识别；多轮对话上下文记忆
- **图文混合渲染**：配方 JSON → 合成/熔炉/锻造/多方块图，只渲染与问题最相关的 Top-3 配方
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
| **Settings** | API Key、嵌入模型名、默认 MC 版本/加载器、用户名 |
| **Metrics** | 查看每次问答的延迟、Token、命中数与历史指标 |

**注意**：Build 是覆盖模式，会清空基础库重新构建，但不会影响 Q&A 阶段生成的会话增量库（`sessions/incremental/`）。

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

- `--test`：读取 `test-questions.json`（多轮对话数组），逐轮走完整 QA 管道并输出 Tier/命中/TF 指标
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
│  UI Layer (JavaFX): Builder / Q&A / Seed / Settings / Metrics │
├─────────────────────────────────────────────────────────┤
│  Agent Layer (agent/)                                   │
│  ├── ClassifyAgent ── LLM 问题分类 (McmodCategory)      │
│  ├── EntityAgent   ── 实体解析 + 别名多线路检索          │
│  ├── UrlAgent      ── LLM 子网页 URL 匹配 + 增量抓取     │
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
  → ClassifyAgent（分类）
  → EntityAgent（实体注册名推断 + 别名向量/字面检索）
  → UrlAgent（LLM 匹配 MC百科子网页 URL / Wiki 镜像主题）
  → IncrementalKnowledgeService（按需抓取 → 分块 → 增量库）
  → VectorStore.searchAcross(base, inc)（双库向量检索）
  → Recipe SQL 精确查询 + 配方相关性评分
  → LLM Reranker（相关度重排）
  → AnswerAgent（Tier 分层合成 Markdown）
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

### 7.2 爬虫安全参数（防限流 / 防 ban IP）

通过 JVM 系统属性（`-D...`）或环境变量配置，所有抓取统一经过限速、退避与缓存机制：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `-Dmaa.scraper.minIntervalMs` | `800` | 每域名最小请求间隔（毫秒），含随机抖动 |
| `-Dmaa.scraper.maxRequestsPerHost` | `2000` | 单次运行每域名请求预算，超限自动停止该域请求 |
| `-Dmaa.scraper.proxy=host:port` | 无 | HTTP 代理逃生通道（IP 被封时使用）；也可用环境变量 `MAA_SCRAPER_PROXY` |

内置防护：403/429/5xx 指数退避 + 抖动并尊重 `Retry-After`；响应缓存（30 分钟 TTL）；反爬页识别（Cloudflare 质询/验证码特征命中即丢弃）；Cookie 会话复用；404 不重试。

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
├── jars/                             # 模组 JAR（如启用复制）
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
  --testweb <dir>       测试网页抓取/清洗/分块/入库链路（默认 test-web.json）
  --api-key <key>       设置 DeepSeek API Key
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
├── MaaKnowledgeBuilderApp.java     # JavaFX 主入口（5 个页面）
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
5. **隐私**：知识库、对话历史、纹理、日志全部存储在本地；API Key 加密存放于 `%USERPROFILE%/.maa_kb/settings.properties`；LLM 调用仅发送问题文本与检索片段，不包含文件路径或个人信息。
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
