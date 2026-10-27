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

**注意**：Build 在临时库完成构建和校验后原子发布；失败或取消时保留原库，也不影响 Q&A 阶段生成的会话增量库（`sessions/incremental/`）。已有知识库默认使用补充模式，完整替换需要选择“重建基础库”。

构建页可使用当前知识库，或选择包含 `rag_data.mv.db` 的已有知识库目录。目录中的图片、纹理和配方资源与数据库一起使用。对其他目录执行构建时独占锁定该目录，完成后关闭数据库；当前问答目录不随之切换。需要对该库问答时，在设置中选择它并重启。

“补充基础库”先继承已有基础数据，再处理 JAR、网址和资料目录中的新增内容。扫描 JAR 时展示新增、已存在与版本/内容冲突；已存在模组不重复进行 Modrinth 绑定，构建仅提取新增模组。新的构建记录 JAR SHA-256；旧库没有指纹时以模组 ID、版本、加载器和 MC 版本保守识别已有模组，并明确提示无法确认字节一致性。同一模组的版本或内容冲突默认跳过，原版本保留。

已导入的网址在联网前跳过；资料目录重新扫描，路径与内容指纹不变的文件不重复解析、OCR 或嵌入，新文件正常导入，同一路径内容变化时保留旧版本并报告冲突。补充模式不刷新旧网页，不删除原位置已不存在的资料；更新已有内容需完整重建并保留全部需要的来源。新目录下同内容文件保留各自来源位置，仍视为不同文档。向量模型指纹不一致时拒绝补充，避免混入不可比较的向量。扫描结果及手动绑定暂存在内存，构建发布前不会写入基础模组数据。

Builder 支持保存完整来源配置，包括模组目录、逐行填写的网址和多个资料目录；重启后恢复配置。保存配置不代表内容已入库，每次成功构建以当前全部来源替换基础知识库。

多来源构建支持 JAR、网址，以及资料目录内的 TXT、DOC、DOCX、PDF、PNG、JPG/JPEG、GIF 和 WebP；网址与资料目录可在没有 JAR 的情况下单独构建。目录递归扫描不跟随符号链接，并排除敏感目录、隐藏文件、临时文件和应用数据目录。构建后的资料可直接用于问答，支持来源范围选择、正文与图片文字联合检索、图文回答及来源回溯。

资料目录入口也识别 MKB-Export 图标包，不做 OCR 或知识向量化。支持 v1 默认状态和 v2 精确组件/NBT 状态；JAR 配方继续独立解析。首次导入后在问答页选择“配方图标”环境。同一来源再次补充 Build 会保存新的资源版本；若该来源原本已选中且环境指纹一致，则更新当前映射，保留历史图片和文本向量。其他环境需手动选择。指定状态找不到时显示原因，不冒用默认图标。参见 [导出及更新说明](docs/export-r4.md)。

`mkb_export` 0.2.2 对完全透明的 GUI 渲染结果记录 `TRANSPARENT`，保留透明 PNG 和哈希，不计入失败；恢复和增量导出也会复用经校验的透明结果。桌面 Build 单列完全透明数量，配方槽标为“透明”，不会借用 JAR 纹理替换。该状态只描述本次渲染的 Alpha，不保证物品设计上始终不可见；模型缺失、渲染和写盘异常仍为错误。旧版桌面不支持该状态，导入此类包时需更新桌面程序。

知识目录新增“来源管理”页：选择已建立的基础数据库，读取来源后，可对整个来源、单篇文档或单个模组预览并执行刷新、按当前解析器重建、删除；整个来源也可替换为同类型的新位置。刷新复用未变化的正文，网页刷新绕过新鲜缓存并使用条件请求；网页解析重建只使用已有原始缓存，不联网补齐缺失页面。目录刷新会解除已移除文件和教程的引用，其他来源仍引用的内容保留。操作在暂存库验证后事务发布，旧会话与图片不删除。旧库未知或混合解析版本不会因补充 Build 被误标为已迁移。参见 [来源生命周期管理](docs/source-lifecycle-r6.md)。

应用图标原图为 `src/main/resources/icon.png`。窗口使用该透明 PNG；Windows `package.bat` 调用 `scripts/PrepareAppIcon.java` 生成多尺寸 ICO，并传给 jpackage。

| 本地格式 | 提取内容 | 定位与图片 |
|---|---|---|
| TXT | UTF-8、带 BOM 的 UTF-16、GB18030 正文 | 保留原始文件位置 |
| DOC / DOCX | 正文段落、表格，DOCX 标题样式 | 提取正文引用的图片及段落位置；不推测 Word 自动排版页码 |
| PDF | 按页提取原生文字，文字稀少页使用本地 OCR | 保留页码、插图坐标；复杂矢量页保存明确标记的整页预览 |
| PNG / JPG / GIF / WebP | 本地 OCR，按设置附加模型描述 | 保留图片资产与来源；动态或多帧图片只识别首帧 |

Windows 10/11 使用系统自带 OCR 和已安装的识别语言，通过 Windows PowerShell 5.1 在本机处理，不上传图片。OCR 不可用、没有识别到文字、预算用尽等状态分别记录；没有内容证据的图片仍可按文件信息查找，明确标为仅元数据。PDF 有足够原生文字时不再做整页 OCR；稀少原文与扫描 OCR 合并去重。普通插图、扫描页与布局预览分别记录，预览不能直接视为普通回答配图。

设置中的“图片文字与视觉描述”默认关闭，单独配置完整的 OpenAI 兼容 Chat Completions 地址、支持图片的模型和 Key。启用后才会将导入图片发送至该服务；不复用 DeepSeek 文本模型凭据。模型描述明确标记为推断。本机 HTTP 或远程 HTTPS 服务可用，云端连接未配置时不影响本地 OCR。

默认限制：TXT 8 MiB，其他本地文件 64 MiB，单张图片 32 MiB；PDF 最多 200 页（`maa.documents.maxPdfPages`）。每次构建最多 OCR 128 张图片、生成 16 次视觉描述，可通过 `maa.documents.maxOcrImages` 和 `maa.documents.maxVisionImages` 调整。超出分析预算仍保留图片并报告部分识别；文件损坏、加密不可读、解析超限或取消会保留原基础库。DOCX 解压上限与图片像素上限防止异常文档耗尽内存。无法直接解码的 Word 插图会报告，复杂 DOC 布局及 PDF 表格不保证还原为原始版式。

`rag_document_import` 记录各文件的处理状态，`rag_content_chunk.extraction_method` 区分原生文字、OCR、模型推断和仅元数据，图片出现位置保留分析状态。构建日志显示文件结果及图片/OCR统计。本机 OCR 验收见 [`test-data/document-import`](test-data/document-import/README.md)，可用 Maven 参数 `-Dtest=LocalOcrLiveTest -Dmaa.live.localOcr=true` 显式运行，测试不联网。

网址按类型处理：MC 百科模组主页复用模组目录构建流程，只访问主页实际出现的非零分类链接；整合包主页读取名称、正文和教程区域内的 `/post/正整数.html` 链接，将关联教程全文分块入库，不继续追踪教程正文中的任意链接。普通网页只采集当前页有效正文和插图，不遍历整个站点。网址直接指向受支持文档或图片时复用文件解析链路，远程资源大小上限为 32 MiB（TXT 仍为 8 MiB）。正文获取失败会保留原基础库，避免发布缺失教程的整合包知识。

网页、分类页和图片共用请求间隔、预算、冷却与退避；原始资源缓存保留 7 天，过期后使用条件请求，缓存受 128 MiB 总预算约束。用户网址及重定向限制为公共 HTTP(S) 服务。

基础库保存来源身份、文档、内容块、图片资产、图片出现位置与图文关联。整合包身份如 `mcmod:modpack:737` 与显示名称分别记录，不冒充游戏 `modId`。图片资产按内容哈希存于 `assets/images/`，发布前校验文件及引用完整性；默认不自动清理，可在设置中检查引用后手动回收。懒加载图片优先读取 `data-src` 等属性，WebP 解码为 PNG 以供 JavaFX 展示，记录图注、章节、原地址和段落关系。现有 JAR 纹理和配方图仍使用原有链路。

网页图片默认每页最多下载 64 次、每次构建最多 256 次，可分别通过 JVM 属性 `maa.build.maxWebImagesPerPage` 和 `maa.build.maxWebImages` 调整（0 关闭下载）。超出预算或下载失败的图片引用仍保留在 `rag_web_image_reference`，构建报告显示未缓存数量；正文索引不因图片下载预算而截断。

网页插图使用本机 OCR，并按图片所在位置绑定具体步骤，区分配方、界面、结构、地图、战斗、外观和装饰用途。数字网格噪声不进入知识向量；由上下文推定的用途会明确标识。补充 Build 时重新填入已导入的网址，可为缓存图片补建 OCR 和步骤索引；局部步骤仅从本地 HTML 缓存恢复，并与已发布正文核对，不联网刷新旧网页。每次构建默认最多 OCR 64 张网页图片（`maa.web.maxOcrImages`），问答按需下载时最多临时 OCR 1 张（`maa.qa.maxImageOcr`），不改写基础库。参见 [攻略配图与验收](docs/guide-images-r5.md)。

整合包专项题集为根目录的 [`golden-set-modpack.json`](golden-set-modpack.json)，沿用黄金集的参考答案、规则断言与模型裁判细则，独立运行 `--eval golden-set-modpack.json`。参考要点按公开 v2.4a 教程核对，覆盖 5 个问题，并要求检索范围及实际引用来源正确；规则命中不替代答案语义核对。构建与检索历史记录见 [`test-data/modpack-737`](test-data/modpack-737/README.md)：已在线验证发现 6 篇教程并完成主页与教程入库，模型生成答案的事实正确性尚未验收。显式联网验收可通过 Maven 参数 `-Dtest=UrlBuildLiveTest -Dmaa.live.modpack737=true` 启用，默认测试不联网；该验收使用每页 2 次、全局 14 次图片下载预算，并将统计写入 `target/stage2-live-report.json`。

问答页的资料范围默认自动识别，也可手动限定整合包、模组或资料目录。问题中的来源名、文件名及“第 N 页”用于限定检索，带“继续”“它”等指代的追问沿用上一轮范围；同名来源需要手动选择。正文语义、词面匹配及关联图片的图注、OCR、模型描述共同参与检索。整合包问题只使用该包资料；仅已绑定实际 JAR 的模组来源可继续使用对应模组的本地配方。没有命中资料时明确提示证据不足，未配置回答模型时展示可追溯的离线摘录。

回答中的插图在相关段落附近展示，点击可缩放查看；来源卡片可查看随回答保存的原文摘录、文件页码、原地址并复制位置或打开原网页。整合包教程来源在末尾按网页地址去重，点击直接打开原网页；“查看检索依据”折叠保留块级摘录，已有会话回放也应用该规则。自然嵌入正文的有效网页链接可保留。扫描页、整页预览、OCR 和模型推断分别标识，普通回答默认排除整页布局预览。模型只能引用本次检索提供的图片及来源标识，程序负责解析真实路径。配方图继续保留，并与资料插图一起保存为内容哈希资产。

已有基础库可使用不调用在线回答模型或网络的 `RetrievalAuditRunner` 检查存储完整性、官方分词样例一致性、逐题候选分数与淘汰原因，并对比历史会话证据。执行方式与字段解释见[检索诊断说明](docs/retrieval-diagnostics.md)。正常问答也将诊断写入指标记录；阈值命中、上下文块数量均不等于真实召回率。

导入资料采用范围内的中英文括注别名、BM25 关键词与向量独立召回、RRF 融合和本地多样性重排。玩法正文优先于密集注册名列表，复合问题兼顾不同检索侧重点；相同正文去重，保留有不同数字或否定的证据。P2 排序改进沿用原 ONNX 模型和向量索引，无需重新建库。诊断可用 `--relevance-labels` 和 `--baseline-audit` 对比固定语料的部分正例召回，不能代替答案事实验收。

P3 的网址与文件导入保存完整原文块、标题层级与表格行，并按现有分词器的 224 Token 预算生成检索块。命中后从相关章节与邻近原文组装有字符上限的上下文，保留页码和提取方式，减少短块遗漏步骤及重复传入正文。既有网址与文件资料需完整重建才能获得结构；补充构建复用旧块并提示，旧库与历史会话仍兼容。跨布局不能直接复用旧块 ID 的召回标注，具体限制见[检索诊断说明](docs/retrieval-diagnostics.md)。

P4 在当前资料范围和页码内检查复合问题的不同侧重点，缺口最多触发两轮、合计六次定向补检，再重新组装有上限的上下文。要点命中只是候选依据，回答仍需核对事实，未找到的部分明确说明。P5 支持从同章节的图注与 OCR 独立召回插图，区分尚未下载、缓存丢失和没有合适图片，控制重复与无关配图；问答的“来源、覆盖范围与性能”可查看补检和图片缓存状态。这两项不更换模型，也不要求再次重建 P3 已生成的结构库。

生成回归另有 [`golden-set-modpack-regression.json`](golden-set-modpack-regression.json)，包含 5 道同义问法和 3 道前提/证据缺口题，与原 5 题分开运行。评测将图片归档到独立运行目录，校验与历史回放使用同一份资产，并区分真实模型回答、离线摘录及模型失败。规则和文本裁判不能证明图片语义相关；缺失证据题检查不展示无关图片。运行命令、用量口径与模型保留结论见[检索诊断说明](docs/retrieval-diagnostics.md)。

“按需缓存来源插图”默认开启：仅补取 Build 已记录、且邻近最终命中正文的图片地址，每次问答默认最多尝试 2 次下载（`maa.qa.imageDownloads`，可设 0–4），每次回答最多选取 4 张资料插图。关闭后仍可使用已有本地图片。下载复用百科的限流、退避与原始资源缓存，不修改已发布知识库；`rag_web_image_reference` 保留构建时的缓存状态，因此问答补取成功不会减少该表的未缓存数量。图片不可用时保留正文和来源。

会话保存来源摘录、图片身份和相对资产路径快照，重启或重新 Build 后仍能回放；删除原始导入文件不影响已保存摘录，删除缓存图片则显示缺图提示。旧会话继续按原格式读取。离线回归及界面冒烟测试说明见 [`test-data/knowledge-answer`](test-data/knowledge-answer/README.md)。

设置页的“图片缓存维护”提供检查、网页图片修复和未引用图片清理。检查统计本地图片、缺失/损坏资产、可回收图片和原始网页缓存占用。修复使用已记录的原图网址，共用现有限流、冷却与缓存，每次最多尝试 4 次获取；只恢复原内容哈希，网址返回的新图不能替换旧会话中的图片。新会话快照保存原图网址及来源页，基础库重建后仍可尝试修复；旧快照缺少这些信息且当前库没有对应记录时，只能显示缺图提示。本地文档及配方图片缺失时需重新构建或生成对应资源。

清理仅管理 `assets/images/` 中符合哈希命名规则的普通图片文件：文件至少保留 7 天，且基础库图片表、聊天会话、评测会话及评测逐题记录均无引用时才能删除。旧会话正文中的图片资产路径也纳入保护。会话损坏、路径异常、符号链接或历史扫描超过预算时禁止清理，不依赖 `listSessions()` 跳过错误后的不完整列表。历史扫描最多读取 10,000 个文件、单文件 32 MiB、总计 128 MiB；失败或取消后可检查提示并重试。维护不修改已发布正文、向量、图文关联或历史快照，修复和清理与构建、问答串行执行。

问答的“来源、覆盖范围与性能”及评测报告区分候选图片、正文实际引用、有效图片、有效资料插图、不可用图片和无效引用；代码示例中的图片/来源标记不参与渲染和统计。`turns.jsonl` 的 `observation.media` 与 `summary.json` 的 `media` 保存这些计数，旧评测记录没有图文统计时不算作已观测轮次。题集可使用 `answer_illustration_valid` 要求至少一张实际展示且有效的资料插图，使用 `answer_media_refs_valid` 检查实际图片与来源引用；后者已加入整合包专项。统计不能证明图片语义相关或答案事实正确，仍需裁判与人工核对。

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
  --build-url <url>     构建网址来源，可重复指定
  --build-documents <dir> 构建资料目录，可重复指定
  --append             补充构建，保留已有知识；不能与 --clean 同用
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

脚本先运行 release 构建，再为每次执行创建新的 `target/e2e-*` 数据目录。顺序为：扫描及 Modrinth slug 绑定 → Bind Url 百科候选绑定 → 从 JAR 和绑定网页构建基础库 → `test-questions.json` 的完整多轮测试 → `golden-set.json` 的规则判分与在线裁判。会消耗已配置的 DeepSeek API Key 对应的额度；凭据仅用于当前进程，不输出、不写入测试目录。全局设置与 GUI 数据目录不改写。`-SkipBuild` 可复用已经编译好的程序，`-FailUnder 0.85` 可设置黄金题集通过率门槛。未设门槛时退出成功仅表示流水线执行完成，需查看质量报告。

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


### Scan → Bind Url → Build

在“构建知识库”中选择目标基础库和 JAR 目录，先“扫描 JAR”，再点击“Bind Url”。百科搜索沿用 modid 查询和既有首个候选逻辑；显示的中文／英文别名与总浏览量用于人工校对，不构成身份验证。百科、搜索站点继续共用限流、冷却和重试规则。

原版固定置顶，名称为“我的世界（Minecraft）”；其余已绑定模组按总浏览降序，浏览量未知的已绑定项随后，未绑定项按 modid 字典序放在末尾。modid、`{slug=…}`、`{url=…}` 分别着色。点击 slug／url 或双击条目可编辑绑定，获取指定主页信息或重新搜索；人工设置不会被批量绑定覆盖。URL 留空保存表示仅构建本地内容。

绑定草稿保存在所选基础库中。Build 仅抓取已绑定的百科主页，不再自行搜索；未绑定的模组仍解析 JAR 配方、名称和本地资源。已有模组换绑后，补充 Build 会提示待发布；应在“知识目录 → 来源管理”选择对应模组，预览并执行“刷新变化内容”，以替换旧网页和向量。刷新未获取到有效正文时保留原库。

在“设置 → 模组条目字段配色”分别为深色、浅色主题调整三种颜色，可预览普通与选中背景并恢复默认。

CLI 对应显式 `--bind-urls [模组目录]`；例如 `--isolated --data-dir <独立目录> --scan <JAR目录> --bind-urls` 仅保存候选草稿，之后使用同一目录执行 `--build <JAR目录>`。`--build` 自身不会隐式绑定百科。在线端到端脚本显式包含 `--bind-urls`。


### Scan 前确认游戏环境

点击“扫描 JAR”后，先定位所选目录中的 Minecraft 客户端本体 JAR；找不到时只检查直属上级目录。识别到后弹窗显示“文件名 + 加载器 + 版本”，可以通过浏览重新选择本体文件、在 NeoForge／Forge／Fabric 中选择加载器、手动输入版本。浏览后重新读取本体版本与可识别的加载器。多个本体候选时提示核对；未找到时停止并提示准备本体 JAR 或选择准确的实例 mods 文件夹。点击“确认并开始 Scan”后才查询模组。

确认的加载器与游戏版本统一用于 Modrinth 筛选，不使用每个模组的依赖范围推断环境。范围仍可作为未提供扫描环境的底层元数据解析信息。确认值按目标基础库和模组来源目录独立保存，供后续 Build 与刷新使用；不会切换软件数据目录。

Modrinth 搜索按 modid → displayName 兜底，并识别完整的 `模组版本+加载器-游戏版本` 发布后缀；不会使用任意版本子串匹配。百科候选也增加 displayName 兜底；遇到限流／网络失败继续保留失败状态。TOML 作者数组合并为作者列表，单个损坏或无法识别的 JAR 记录后跳过。

CLI 可用 `--game-jar <本体文件> --game-loader forge --game-version 1.20.1` 明确指定环境；未提供覆盖值时从唯一的本体候选读取。加载器未知或候选不唯一时必须指定，CLI 不发起交互式确认。


### 扫描记录、进度与配色

“已识别模组”标题旁显示 Scan、Bind Url、Build 的已完成／总计。Scan 包含已处理的无元数据 JAR；Bind Url 包含已处理的未匹配与保留项，失败保留为待重试。Build 按 JAR、Wiki、百科、资料来源等阶段计数，并显示阶段名称；所有内容校验并发布后才标记“已发布”。

设置中的字段配色同时提供三种进度颜色，可分别为深色、浅色主题自定义。默认 Scan 为 modid 与 slug RGB 颜色的1:1线性插值；Bind Url 跟随 url；Build 为紫色。勾选“使用默认”时，修改字段颜色也会更新关联的进度颜色。

Scan 自动在所选模组目录写入 `.mkb-pre.json`，保存确认环境、公开模组元数据、slug、百科绑定及 JAR 内容摘要，不包含 API Key。重启后同一基础库记住最后扫描目录，存在记录时自动校验恢复，也可点击“读取预处理记录”。未变文件复用结果；新增／变化文件需 Scan 补齐。重扫只查询新增、变化、网络失败或过期的未匹配项。自动未匹配结果保留24小时；需要立即重新查询时可以删除记录后 Scan，已保存的人工绑定草稿仍保留。Bind Url 和人工编辑会同步更新记录。目录不可写时明确报告保存失败，本次已识别结果仍可使用。

构建日志记录 Scan 的总耗时、内容校验、元数据、Modrinth 查询耗时、HTTP 请求数与复用数量。Modrinth 查询是独立服务；百科搜索、主站页面及重试仍共享应用进程内的百科限流与冷却。

Scan 与 Bind Url 共用 `.mkb-pre.json`，兼容读取旧 `.mkb-scan.json`，完整恢复或下次保存时迁移为新文件。记录包含两个阶段的 completed/total；界面按实际文件校验后的结果恢复进度，网络失败不算阶段完成。未找到百科主页计为已完成查询；可人工补充，或继续后续操作。


### JAR 语言资源的损坏与回退

JAR名称索引读取各命名空间的中英Lang；文本向量按当前modid优先中文，损坏或没有可用内容时尝试英文。坏语言文件仅产生包含路径和位置的构建警告，其他资源继续导入；不修改原JAR。数据库错误和材料标签错误仍会中止发布并保留原库。旧库需要通过来源管理按当前解析器重建，才能补齐旧Lang向量；重复补充Build只按需补建资源索引。

具体提取内容、原始纹理与MKB-Export的关系、Lang过滤与分块规则见 [JAR与语言资源说明](docs/jar-language-import.md)。
