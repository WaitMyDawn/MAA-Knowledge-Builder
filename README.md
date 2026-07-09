# MAA Knowledge Builder

**Agentic RAG 图文混合知识问答系统 — Minecraft 模组知识库构建与智能问答**

[![Java](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)
[![JavaFX](https://img.shields.io/badge/JavaFX-21.0.11-orange)](https://openjfx.io/)
[![License](https://img.shields.io/badge/License-All%20Rights%20Reserved-red)](LICENSE)

---

## 目录

- [简介](#简介)
- [功能特性](#功能特性)
- [快速开始](#快速开始)
- [使用指南](#使用指南)
- [架构概览](#架构概览)
- [技术栈](#技术栈)
- [模型与数据来源](#模型与数据来源)
- [合法性与合规声明](#合法性与合规声明)
- [构建与打包](#构建与打包)
- [项目结构](#项目结构)
- [许可证](#许可证)

---

## 简介

MAA Knowledge Builder 是一款 **Java 21 桌面应用**，面向 Minecraft 模组玩家与整合包作者，提供：

- **离线知识库构建**：从本地 JAR 文件（Minecraft 本体 + NeoForge/Forge/Fabric 模组）自动提取纹理、配方、多语言文本，构建向量知识库
- **Agentic RAG 智能问答**：四 Agent 协作管道（ClassifyAgent → EntityAgent → UrlAgent → AnswerAgent），支持中文自然语言提问，自动推断物品注册名，LLM 匹配 MC 百科子网页，按需增量抓取知识
- **图文渲染**：根据配方 JSON 动态生成合成图、熔炉图、锻造图
- **训练种子**：导出/导入 `.maa-seed.json` 文件，在 MAA 社区分享知识库构建方案

## 功能特性

### 知识库构建
- 解析 `neoforge.mods.toml` / `mods.toml` / `fabric.mod.json` 自动识别模组元数据
- 从 `assets/{modid}/textures/` 提取所有纹理 PNG
- 从 `assets/{modid}/textures/entity/` 扫描实体注册名
- 从 `data/{modid}/recipe/` 提取所有配方 JSON
- 从 `assets/{modid}/lang/zh_cn.json` 提取中英文物品名映射
- 通过 **Modrinth API v2** 精确绑定模组 slug（版本号精确匹配）
- 从 **中文 Minecraft Wiki**（zh.minecraft.wiki）抓取 16 个基础页面
- 从 **MC 百科**（mcmod.cn）按模组分类深度抓取，构建 `subWebPage` 映射

### Agentic RAG 问答
- **ClassifyAgent**：LLM 将问题分类到 11 个 MC 百科分类（物品/方块、生物/实体、多方块结构…）
- **EntityAgent**：基于模组清单 + 实体注册名提示 → LLM 推断 `modid:item_name` 格式注册名
- **UrlAgent**：将 EntityAgent 推断出的实体中文名 + modId → 从该 mod 的 `中文名(英文名)→URL` 映射中用 LLM 匹配最佳 MC 百科子网页
- **AnswerAgent**：Tier A/B/C/D 四级数据分层策略，数据充足时精准引用，稀疏时补充 AI 知识并声明来源
- **增量知识获取**：对话中按需从 MC 百科子网页抓取内容 → 向量化 → 存入增量数据库
- **对话上下文记忆**：自动追踪最近对话，识别代词（"它们"、"这个"）和追问

### 可观测性
- 每次问答全链路计时（分类/实体解析/增量抓取/向量检索/配方搜索/答案生成）
- 性能/质量/成本指标实时显示（延迟、实体数、向量命中数、Token 估算）
- 文件日志：`logs/yyyy-MM-dd-x.log`，完整记录操作与错误

### 其他
- 训练种子导入/导出（`.maa-seed.json`）
- 多轮对话历史持久化
- 增量数据库按会话独立管理，支持清理

---

## 快速开始

### 环境要求

| 依赖 | 版本 |
|------|------|
| JDK | 21+ |
| JavaFX SDK | 21.0.11 |
| Maven | 3.8+（推荐使用内置 `mvnw`） |
| 操作系统 | Windows 10/11（macOS/Linux 需调整脚本） |

### 获取 API Key（可选，但推荐）

1. 注册 [DeepSeek](https://platform.deepseek.com/) 账号
2. 获取 API Key（`sk-xxxxxxxx`）
3. 在软件 Settings 页面填入

> 无 API Key 时，Q&A 使用离线模式显示原始检索结果。

### 运行开发模式

```bash
# 克隆项目
git clone https://github.com/WaitMyDawn/MAA-Knowledge-Builder.git
cd MAA-Knowledge-Builder

# 编译并运行
./mvnw javafx:run
```

### 一键打包

```bash
# 确保已安装 JavaFX SDK 21.0.11
# 默认路径: D:\javafx-sdk-21.0.11 或 C:\javafx-sdk-21.0.11
# 或设置环境变量: set JAVAFX_SDK=D:\path\to\javafx-sdk-21.0.11

package.bat
```

打包输出位于 `build\package\MAA-Knowledge-Builder\`，双击 `MAA-Knowledge-Builder.exe` 运行。

---

## 使用指南

### 1. 首次启动

选择数据目录（所有知识库数据存储于此），软件自动初始化 H2 数据库和 ONNX 嵌入模型。

### 2. 构建知识库

```
Builder Tab → Browse 选择模组文件夹 → Scan JARs → Build Knowledge Base
```

**注意**：Build 会清空上次构建的数据（覆盖模式），但不会影响 Q&A 阶段产生的增量数据库。

### 3. 问答

```
QA Tab → 输入问题 → Ask
```

示例提问：
- "龙有哪几种"
- "指南针怎么做"
- "冰火传说如何养龙"
- "介绍一下它们"（基于对话上下文的追问）

---

## 架构概览

```
┌─────────────────────────────────────────────────────────┐
│                    MAA Knowledge Builder                │
├─────────────────────────────────────────────────────────┤
│  UI Layer (JavaFX)                                      │
│  ├── Builder Tab  ── Q&A Tab  ── Seed Mgr  ── Settings │
├─────────────────────────────────────────────────────────┤
│  Agent Layer (agent/)                                   │
│  ├── ClassifyAgent   ── LLM 问题分类 (McmodCategory)    │
│  ├── EntityAgent     ── LLM 注册名推断                  │
│  ├── UrlAgent        ── LLM 子网页 URL 匹配             │
│  └── AnswerAgent     ── LLM 分层答案合成 (Tier A/B/C/D)│
├─────────────────────────────────────────────────────────┤
│  Service Layer (service/)                               │
│  ├── QaPipeline          ── Agent 管道编排 + 指标采集   │
│  ├── KnowledgeBuilder    ── 构建编排                    │
│  ├── VectorStore         ── H2 向量库 + 多 DB 检索     │
│  ├── EmbeddingService    ── ONNX 384-dim 嵌入           │
│  ├── WikiScraperService  ── MC Wiki + MC百科抓取        │
│  └── ModrinthBinder      ── Modrinth slug 绑定          │
├─────────────────────────────────────────────────────────┤
│  Data Layer (model/)                                    │
│  ├── DatabaseBuilder     ── H2 DDL/CRUD                 │
│  ├── 基础库: rag_data.db                                │
│  └── 增量库: sessions/incremental/rag_data_{sid}.db    │
└─────────────────────────────────────────────────────────┘
```

### 数据流

```
用户提问
  → ClassifyAgent (分类)
  → EntityAgent (推断实体注册名)
  → UrlAgent (LLM 匹配子网页 URL)
  → IncrementalKnowledgeService (按需抓取 MC百科子网页)
  → VectorStore.searchAcross([base, inc], queryVec, 20) (双库检索)
  → Recipe SQL 精确查询
  → LLM Reranker (相关度重排)
  → AnswerAgent (Tier 分层合成)
  → Markdown + 图片 → UI 显示
```

---

## 技术栈

| 层 | 技术 | 版本 | 用途 |
|---|---|---|---|
| **语言** | Java | 21 | Record/虚拟线程/Pattern Matching |
| **UI** | JavaFX + ControlsFX | 21.0.11 / 11.2.1 | 桌面 GUI、Markdown 渲染 |
| **AI Agent** | LangChain4j | 0.29.1 | DeepSeek API 封装（OpenAI 协议） |
| **嵌入** | ONNX Runtime | 1.18.0 | all-MiniLM-L6-v2 384-dim 语义向量 |
| **数据库** | H2 | 2.2.224 | 嵌入式向量库（BLOB + 余弦相似度） |
| **爬虫** | Jsoup | 1.18.1 | HTML 解析与网页抓取 |
| **JSON** | Jackson | 2.17.1 | 配方/种子/子网页映射 序列化 |
| **TOML** | night-config | 3.6.6 | neoforge.mods.toml 解析 |
| **日志** | SLF4J + Logback | 2.0.9 / 1.4.14 | 控制台 + 文件双输出 |
| **构建** | Maven | 3.8+ | shade/assembly/javafx 多插件构建 |
| **打包** | jpackage | JDK 21 | 原生 EXE + 捆绑 JRE |

---

## 模型与数据来源

### all-MiniLM-L6-v2（嵌入模型）

| 属性 | 详情 |
|------|------|
| **名称** | all-MiniLM-L6-v2 |
| **来源** | [sentence-transformers / Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2) |
| **许可证** | **Apache License 2.0** |
| **格式** | 预转换为 ONNX（Open Neural Network Exchange） |
| **维度** | 384-dim |
| **大小** | ~86 MB |
| **用途** | 将中文/英文文本转换为语义向量，用于知识库的相似度检索 |

> 本项目对原始 PyTorch 模型进行了 ONNX 格式转换以支持 Java 运行时推理。转换过程仅涉及格式变更，不改变模型权重和架构。Apache 2.0 许可证允许修改和再分发。

### DeepSeek API（大语言模型）

| 属性 | 详情 |
|------|------|
| **提供商** | 深度求索（DeepSeek） |
| **模型** | deepseek-chat |
| **API 端点** | `https://api.deepseek.com/v1` |
| **协议** | OpenAI 兼容 |
| **用途** | ClassifyAgent / EntityAgent / AnswerAgent 的推理决策 |

> 用户需自行注册 DeepSeek 账号并获取 API Key。API 调用费用由 DeepSeek 按其定价政策收取，与本项目无关。

### Minecraft Wiki（知识来源）

| 属性 | 详情 |
|------|------|
| **URL** | `https://zh.minecraft.wiki` |
| **内容许可** | [CC BY-NC-SA 3.0](https://minecraft.wiki/w/Minecraft_Wiki:Copyrights) |
| **用途** | Build 阶段抓取 16 个基础页面（合成、烧炼、附魔…）的文本内容用于知识库构建 |

> 本软件仅限个人使用。用户构建的知识库存储在本地，不对外分发。

### MC百科（知识来源）

| 属性 | 详情 |
|------|------|
| **URL** | `https://www.mcmod.cn` |
| **用途** | Build 阶段获取模组物品列表 + subWebPage 映射；Q&A 阶段按需从具体物品页面获取内容 |

> 本软件仅在分类页面层级抓取 MC 百科（不访问单个物品子页面），访问频率受到内置延迟限制，遵循合理使用原则。

### Modrinth API

| 属性 | 详情 |
|------|------|
| **API** | `https://api.modrinth.com/v2` |
| **用途** | Scan 阶段通过模组 ID + 版本号精确匹配 Modrinth slug |
| **许可** | [Modrinth API 免费公开使用](https://docs.modrinth.com/) |

---

## 合法性与合规声明

### 知识产权

1. **Minecraft** 是 Mojang Studios / Microsoft 的注册商标。本项目是独立开发的第三方工具，与 Mojang/Microsoft 无关联。
2. **模组 JAR 文件**：本软件在用户本地计算机上解析用户已合法获取的模组 JAR 文件，仅提取纹理和配方元数据用于个人知识库构建。不修改、不重新分发原始模组文件。
3. **MC 百科内容**：本软件抓取的 MC 百科网页内容仅缓存在用户本地知识库中供个人查询使用，不对外分发或商业利用。
4. **训练种子（.maa-seed.json）**：种子文件仅包含模组标识符（slug/modId）和网页资源 URL 的引用，不包含任何版权内容（纹理、配方 JSON、数据库文件）。

### 隐私

- 所有数据（知识库、对话历史、纹理、日志）**完全存储于用户本地**，不上传至任何服务器。
- API Key 加密存储于 `%USERPROFILE%/.maa_kb/settings.properties`。
- LLM API 调用仅发送问题文本和检索到的文本片段，不包含文件路径或个人身份信息。

### 使用限制

- 本软件仅供个人学习和研究使用。
- 请勿用于大规模商业爬取或违反目标网站服务条款的行为。
- 使用者应遵守 MC 百科和 Minecraft Wiki 的 robots.txt 及服务条款。

---

## 构建与打包

### 开发编译

```bash
./mvnw compile
./mvnw javafx:run
```

### 打包为可执行文件

```bash
# Windows
package.bat

# 输出
build/package/MAA-Knowledge-Builder/
├── MAA-Knowledge-Builder.exe
├── app/
│   ├── MAA-Knowledge-Builder-shaded.jar
│   ├── javafx/          (JavaFX JARs + native DLLs)
│   └── MAA-Knowledge-Builder.cfg
└── runtime/              (Bundled JRE)
```

### Maven 配置要点

- `maven-shade-plugin`：生成 fat JAR（排除 JavaFX 依赖，避免与 jpackage 捆绑的 JavaFX 冲突）
- `maven-dependency-plugin`：复制依赖到 `target/lib/`
- `javafx-maven-plugin`：开发时 `mvn javafx:run`

---

## 项目结构

```
src/main/java/yagen/waitmydawn/kb/
├── agent/
│   ├── ClassifyAgent.java          # Agent 1: LLM 问题分类
│   ├── EntityAgent.java            # Agent 2: LLM 实体注册名推断
│   └── AnswerAgent.java            # Agent 3: LLM 分层答案合成
├── config/
│   ├── AppConfig.java              # 全局配置管理
│   └── LogSetup.java               # 文件日志初始化
├── dto/
│   ├── ClassificationResult.java   # 分类结果 DTO
│   ├── QaMetrics.java              # 可观测性指标
│   └── RetrievalResult.java        # 检索结果 DTO
├── model/
│   ├── DatabaseBuilder.java        # H2 数据库管理
│   ├── ModEntry.java               # 模组元数据
│   ├── TrainingSeed.java           # 训练种子
│   └── ...                         # RagItem/RagRecipe/RagMultiblock/...
├── renderer/
│   ├── CraftingRenderer.java       # 合成图渲染
│   ├── FurnaceRenderer.java        # 熔炉图渲染
│   ├── MultiblockRenderer.java     # 多方块图渲染
│   └── ...                         # BlockRenderer/SmithingRenderer/...
├── service/
│   ├── QaPipeline.java             # Agent 管道编排
│   ├── KnowledgeBuilder.java       # 知识库构建
│   ├── VectorStore.java            # 向量存储 + 多 DB 搜索
│   ├── EmbeddingService.java       # ONNX 嵌入 + 回退
│   ├── WikiScraperService.java     # MC Wiki + MC百科抓取
│   ├── ModrinthBinder.java         # Modrinth slug 绑定
│   ├── EntityTextureScanner.java   # 实体纹理扫描
│   ├── IncrementalDB.java          # 增量数据库
│   ├── IncrementalKnowledgeService.java  # 增量知识获取
│   ├── MultiDBManager.java         # 多 DB 管理
│   ├── RagAgentService.java        # LLM 调用封装
│   ├── TextChunker.java            # 文本分块
│   ├── TextureExtractor.java       # 纹理提取
│   ├── RecipeExtractor.java        # 配方提取
│   ├── JarScannerService.java      # JAR 扫描
│   ├── ModMetadataParser.java      # 元数据解析
│   ├── ChatHistoryService.java     # 对话历史
│   └── ...                         # EntityResolver/SeedExport/SeedImport/...
├── ui/
│   └── MarkdownRenderer.java       # Markdown → JavaFX TextFlow
└── MaaKnowledgeBuilderApp.java     # 主入口
```

---

## 许可证

```
Copyright (c) 2026 Yagen. All Rights Reserved.
```

本项目代码保留所有权利。未经明确授权，禁止复制、修改、分发或商业使用。

具体许可条款参见 [LICENSE](LICENSE.md)。

> 注意：本项目包含的第三方组件各自适用其原始许可证。

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

---

> *MAA Knowledge Builder — 让 AI 理解你的 Minecraft 世界。*
