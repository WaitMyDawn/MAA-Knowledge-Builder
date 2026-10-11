# 八项复核问题的最终回归回答

本文件保存冻结代码的实际输出，不是参考答案。运行：`run-20261011-185317-42e4762b-adjudication-verified`。

## O-MT-T01 — 末影龙怎么打？

规则：True；裁判：pass。

# [末影龙](https://wiki.biligame.com/mc/末影龙)打法指南

## 前提说明

**[末影龙](https://wiki.biligame.com/mc/末影龙)**（`minecraft:ender_dragon`）是原版 Minecraft 的最终 Boss，位于末地。以下内容基于本地知识库中的中文 Minecraft Wiki 资料整理，适用于当前安装的 **Minecraft Java 1.21.1（vanilla）**。

> ⚠️ **版本排除**：本地知识库教程页面提到的"矛"（spear）是 Java 1.21.11 新增武器，当前安装版本（1.21.1）**没有此物品**，因此本指南不包含任何关于矛的战术建议。来源：https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11

---

## 一、了解[末影龙](https://wiki.biligame.com/mc/末影龙)

- **生命值**：200（× 100）
- **类型**：敌对生物
- **四种攻击方式**：
  - **冲撞**：简单 6 / 普通 10 / 困难 15 点伤害
  - **扑翅**：简单 3.5 / 普通 5 / 困难 7.5 点伤害
  - **龙息**：区域效果云，每秒 3 点伤害（Java 版）
  - **[末影龙](https://wiki.biligame.com/mc/末影龙)火球**：区域效果云，每秒 6 点伤害（Java 版）

**关键机制**：你破坏的末地水晶越多，[末影龙](https://wiki.biligame.com/mc/末影龙)攻击你的频率就越高。Java 版中[末影龙](https://wiki.biligame.com/mc/末影龙)有很强的击退能力，能把你撞到空中致命高度。

---

## 二、战备清单

**核心物品**：
- **床 / 重生锚 / TNT**：用于对[末影龙](https://wiki.biligame.com/mc/末影龙)造成大量爆炸伤害。床可现场用羊毛+木板合成以节省物品栏；重生锚成本高但可堆叠；TNT 是下位替代（威力低且引爆需时间）。
- **弓或弩**：弩可附魔**快速装填**（多重射击提高命中率，穿透无用）。
- **烟花火箭**（多放烟火之星）：作为弩的弹药，有爆炸效果。
- **大量雪球或鸡蛋**：用于打碎末地水晶（若箭充足或弓有无限附魔则不需要）。
- **玻璃瓶**：收集龙息用。
- **末影箱**：保管无用物品、储存玻璃瓶和龙息。

**辅助物品**：
- **雕刻南瓜**：戴上避免与末影人对视激怒它们。
- **力量药水 II**：提高对[末影龙](https://wiki.biligame.com/mc/末影龙)的伤害。
- **缓降药水**：应对摔落伤害（获取较困难）。
- **奶桶**：清除不需要的状态效果。
- **1-2 个不死图腾**。
- **大量脚手架**：登上黑曜石柱，比普通方块更方便。
- **大量末影珍珠**：快速闪避、落地前传送规避摔落伤害、避免掉入虚空。建议进入末地后击杀主岛末影人获取。

---

## 三、战斗流程

### 第一阶段：摧毁末地水晶

末地水晶会给龙回血，**建议摧毁所有水晶**。

- **远程摧毁**：用弓或弩射爆水晶。火矢可辅助瞄准（Java 版可用 F3 + B 显示碰撞箱）。通过爆炸效果、龙受伤情况或判定箱确认是否摧毁。
- **铁栏杆保护的水晶**：几乎无法从地面射中。可在黑曜石塔底部，从铁栏杆下的空隙射击（Java 版）。
- **攀爬黑曜石柱**：优先攀爬有铁笼保护水晶的柱子，用近战摧毁水晶，并站在柱子上射击其他水晶。
  - 用水桶反复"装水—倒在更高方块"来攀爬，意外摔落也不受伤。
  - 用末影珍珠比搭方块/爬梯更快更安全（龙可能在你搭方块时把你打下来）。测量落点技巧：先拉满弓射箭或扔雪球，以相同角度扔末影珍珠会命中箭的落点。
- **时机技巧**：在水晶给龙回血时破坏它，可对龙造成 10 点伤害。

### 第二阶段：攻击[末影龙](https://wiki.biligame.com/mc/末影龙)

- **栖息阶段（降落停驻在末地祭坛上方）是攻击良机**：破坏的水晶越多，龙越频繁栖息。提前蹲在末地祭坛中，等龙在祭坛上方盘旋时不断射箭。
- **栖息时对弹射物免疫**：龙降落停驻在祭坛上时，**不能被箭或三叉戟击中**（Java 版），射向它的箭或三叉戟会被弹开并着火。此时应改用**近战攻击**瞄准龙头。注意：这是**栖息阶段**的弹射物免疫，**不是飞行阶段的免疫**——龙在飞行/盘旋时仍可被弹射物命中。
- **注意**：Java 版中**跳劈无效**——暴击不会对[末影龙](https://wiki.biligame.com/mc/末影龙)造成额外伤害，跳跃时还可能被击飞。

### 躲避伤害

- 一直注意龙的动向，一旦发现它发射火球、龙息或向你冲来，**立刻疾跑躲开**。
- 被打到空中时：**落地水**规避摔落伤害；操作要求更低的方式是向下方丢**末影珍珠**（但造成 5 点伤害）；接近落地时扔**风弹**也可降低落地伤害。

---

## 四、收集龙息（可选）

龙息可用于制造滞留药水和药水箭，收集还能减少其作用时间和范围。

- 龙吐息或发射火球后，地面滞留的紫色云雾即龙息。**对准龙息使用玻璃瓶**即可收集。
- **不要站在龙息里**以免受伤。
- 通常一个[末影龙](https://wiki.biligame.com/mc/末影龙)火球产生的气雾可收集 **12 瓶**龙息；近战喷射的气雾可收集 **2-4 瓶**。

---

## 五、击败后

击败[末影龙](https://wiki.biligame.com/mc/末影龙)后会生成**龙蛋**和**末地传送门**。

---

**补充说明**：以上为原版机制，属于通用知识。如需了解特定模组中[末影龙](https://wiki.biligame.com/mc/末影龙)的改动，请说明模组名称。

---

## O-M02-T01 — 怎么用酿造台做出治疗药水？

规则：True；裁判：pass。

## 用[酿造台](https://wiki.biligame.com/mc/酿造台)制作[治疗药水](https://wiki.biligame.com/mc/治疗药水)

### 前提：先合成[酿造台](https://wiki.biligame.com/mc/酿造台)

**[酿造台](https://wiki.biligame.com/mc/酿造台)**（`minecraft:brewing_stand`，原版 Minecraft）的合成配方（JAR 解析验证）：

```
 B 
###
```

- **烈焰棒** × 1（`minecraft:blaze_rod`）
- 标签 `#minecraft:stone_crafting_materials`（任意类圆石方块）× 3

烈焰棒需在下界要塞击杀烈焰人获得；也可以直接在村庄、雪屋或末地船找到现成的[酿造台](https://wiki.biligame.com/mc/酿造台)。

### 酿造流程（两步）

[酿造台](https://wiki.biligame.com/mc/酿造台)需要**烈焰粉**作为燃料（每份燃料可支持若干次酿造）。

**第一步：水瓶 → [粗制的药水](https://wiki.biligame.com/mc/粗制的药水)**

- 材料：**下界疣** + **水瓶**（或喷溅型/滞留型水瓶）
- 产出：**[粗制的药水](https://wiki.biligame.com/mc/粗制的药水)**（`minecraft:potion`，粗制）

**第二步：[粗制的药水](https://wiki.biligame.com/mc/粗制的药水) → [治疗药水](https://wiki.biligame.com/mc/治疗药水)**

- 材料：**[闪烁的西瓜片](https://wiki.biligame.com/mc/闪烁的西瓜片)** + **[粗制的药水](https://wiki.biligame.com/mc/粗制的药水)**（或喷溅型/滞留型[粗制的药水](https://wiki.biligame.com/mc/粗制的药水)）
- 产出：**[治疗药水](https://wiki.biligame.com/mc/治疗药水)**（`minecraft:potion`，治疗）

### [闪烁的西瓜片](https://wiki.biligame.com/mc/闪烁的西瓜片)怎么来

**[闪烁的西瓜片](https://wiki.biligame.com/mc/闪烁的西瓜片)**（`minecraft:glistering_melon_slice`）由**金粒 + 西瓜片**合成。它不能食用，专门用于酿造能瞬间治疗的[治疗药水](https://wiki.biligame.com/mc/治疗药水)。也可通过农民大师级交易（绿宝石 × 4 换 3 个）或废弃传送门战利品箱获得。

### 可选：增强版与变体

- **增强版[治疗药水](https://wiki.biligame.com/mc/治疗药水)**：**荧石粉** + [治疗药水](https://wiki.biligame.com/mc/治疗药水)
- **喷溅型[治疗药水](https://wiki.biligame.com/mc/治疗药水)**：**火药** + [治疗药水](https://wiki.biligame.com/mc/治疗药水)（或增强版[治疗药水](https://wiki.biligame.com/mc/治疗药水)）
- **滞留型[治疗药水](https://wiki.biligame.com/mc/治疗药水)**：**龙息** + 喷溅型[治疗药水](https://wiki.biligame.com/mc/治疗药水)
- **治疗之箭**：**箭** + 滞留型[治疗药水](https://wiki.biligame.com/mc/治疗药水)（或增强版滞留型[治疗药水](https://wiki.biligame.com/mc/治疗药水)），一次合成 8 支

### [酿造台](https://wiki.biligame.com/mc/酿造台)使用要点

- [酿造台](https://wiki.biligame.com/mc/酿造台)**没有"输出"槽位**，放入的药水会在酿造过程中被直接转换，因此可以连续投入多种材料逐步改变药水。
- 与漏斗交互：上方漏斗输入酿造材料，侧面漏斗输入药水与烈焰粉燃料，下方漏斗取出药水（未激活时总是会取走药水）。
- 有效配方可预先加入，用于复杂配方的自动酿造，下界疣通常会在第一步进入。

> 说明：以上酿造配方与合成配方均来自本地知识库中的 Minecraft Wiki 镜像证据（[治疗药水](https://wiki.biligame.com/mc/治疗药水)、[粗制的药水](https://wiki.biligame.com/mc/粗制的药水)、[闪烁的西瓜片](https://wiki.biligame.com/mc/闪烁的西瓜片)、[酿造台](https://wiki.biligame.com/mc/酿造台)），属于原版通用机制，适用于当前安装的 Java 1.21.1。

---

## O-M03-T01 — 原版怎么获得鞘翅？

规则：True；裁判：pass。

## 原版[鞘翅](https://wiki.biligame.com/mc/鞘翅)的获取方式

**[鞘翅](https://wiki.biligame.com/mc/鞘翅)**（`minecraft:elytra`，来自原版 Minecraft）无法通过合成获得，只能通过探索末地来获取。

### 获取途径：末地船

根据中文 Minecraft Wiki 镜像的资料：

- [鞘翅](https://wiki.biligame.com/mc/鞘翅)**仅在末地船里自然生成**。
- 每个末地船里都会有一个**物品展示框**，当中陈列着一个[鞘翅](https://wiki.biligame.com/mc/鞘翅)。
- 一只**潜影贝**守护着[鞘翅](https://wiki.biligame.com/mc/鞘翅)和两个宝箱。

### 获取步骤

1. 击败末影龙，进入末地外岛（通过末地折跃门传送）。
2. 在末地外岛寻找**末地城**，末地城旁通常连接着**末地船**。
3. 进入末地船，找到陈列[鞘翅](https://wiki.biligame.com/mc/鞘翅)的物品展示框。
4. 注意船内有一只**潜影贝**守护，需先将其击败或规避，再取走[鞘翅](https://wiki.biligame.com/mc/鞘翅)。

### 补充信息

- **稀有度**：史诗
- **最大耐久度**：432
- **最大堆叠**：1
- **可再生**：否（每个末地船的[鞘翅](https://wiki.biligame.com/mc/鞘翅)数量有限，无法通过繁殖或合成再生）
- **物品ID**：`elytra`

### 修复与强化（可选）

- 在**铁砧**上，每个**幻翼膜**可恢复[鞘翅](https://wiki.biligame.com/mc/鞘翅) 25% 的耐久（108 点，向下取整）。
- 两个[鞘翅](https://wiki.biligame.com/mc/鞘翅)合并时，会叠加剩余耐久度并额外获得 12% 的耐久度，同时按铁砧机制保留魔咒。
- [鞘翅](https://wiki.biligame.com/mc/鞘翅)可附魔**耐久 III**、**经验修补 I**、**消失诅咒 I**，但只能通过铁砧获得。

> 说明：以上信息来自本地知识库中的中文 Minecraft Wiki 镜像页面（[鞘翅](https://wiki.biligame.com/mc/鞘翅)）。[鞘翅](https://wiki.biligame.com/mc/鞘翅)属于原版物品，其获取机制在各版本中基本一致。

---

## O-A04-T01 — 原版 Minecraft 里有哪些枪械？

规则：True；裁判：pass。

## 结论：原版 Minecraft 没有枪械

**原版 Minecraft（Java 版 1.21.1）中不存在任何枪械类[物品](https://wiki.biligame.com/mc/物品)。** 这是一个需要先纠正的前提问题——「枪械」属于模组（Mod）内容，而非原版游戏内容。

### 证据说明

本地知识库检索到的原版相关页面（[教程](https://wiki.biligame.com/mc/教程)、[物品](https://wiki.biligame.com/mc/物品)、[交易](https://wiki.biligame.com/mc/交易)、[锻造](https://wiki.biligame.com/mc/锻造)、[命令](https://wiki.biligame.com/mc/命令)）中，**均未出现任何枪械类[物品](https://wiki.biligame.com/mc/物品)**。这些页面只覆盖原版玩法、[物品](https://wiki.biligame.com/mc/物品)栏物体、村民[交易](https://wiki.biligame.com/mc/交易)、盔甲纹饰[锻造](https://wiki.biligame.com/mc/锻造)和[命令](https://wiki.biligame.com/mc/命令)系统，没有任何枪械条目。

### 关于「矛」的版本提醒

需要特别说明：**矛（spear）是 Java 版 1.21.11 新增的武器**，而当前安装版本为 **1.21.1**，**不包含该[物品](https://wiki.biligame.com/mc/物品)**。因此不能把新版 Wiki 中的矛及其配套战术当作当前版本的玩法来推荐。

> 来源：https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-11

### 如果你想要枪械玩法

原版无法提供枪械，需要安装模组。常见的枪械类模组（如 *Vic's Point Blank*、*MrCrayfish's Gun Mod*、*Timeless and Classics* 等）属于第三方内容，**本地知识库未收录这些模组的具体数据**，因此无法确认其[物品](https://wiki.biligame.com/mc/物品)、配方或机制。

### 建议

如需更详细的信息，请尝试更具体地描述你的问题，例如：
- 指定模组名称（如「Vic's Point Blank 里有哪些枪？」）
- 或询问特定方面（合成配方、弹药类型、装填机制等）

这样我才能基于已构建的知识库给出准确回答。

---

## IAF-MT-T02 — 它们怎么驯服？

规则：True；裁判：pass。

## 结论先说：冰火传说的龙**不能像普通宠物那样直接驯服**

根据本地知识库的[冰龙](https://www.mcmod.cn/item/72648.html)、[火龙](https://www.mcmod.cn/item/72647.html)、[电龙](https://www.mcmod.cn/item/790978.html)页面，**生存模式下无法通过常规手段驯服野生龙**。获得驯服龙的主要途径是**孵化[龙蛋](https://www.mcmod.cn/item/192989.html)**——孵化出的小龙会把放置[龙蛋](https://www.mcmod.cn/item/192989.html)的玩家视作主人。

- [火龙](https://www.mcmod.cn/item/72647.html)页面明确写道：**野生[火龙](https://www.mcmod.cn/item/72647.html)只能使用创造模式专属的「创造模式[龙食](https://www.mcmod.cn/item/62073.html)」驯服，生存模式下只能通过孵化[龙蛋](https://www.mcmod.cn/item/192989.html)获得驯服的[火龙](https://www.mcmod.cn/item/72647.html)**（页面另提到经测试「深渊国度的塞壬之歌卷轴」也可驯服，属跨模组特例）。
- [冰龙](https://www.mcmod.cn/item/72648.html)、[电龙](https://www.mcmod.cn/item/790978.html)页面未给出「直接驯服野生龙」的方法，只给出孵化与繁殖机制。

> 注意：知识库中大量「驯龙通用」内容（乘骑、面板、号角、晶石等）都是针对**已经驯服的龙**的操作，**不是**驯服野生龙的方法。

---

## 一、[冰龙](https://www.mcmod.cn/item/72648.html)（[Ice Dragon](https://www.mcmod.cn/item/72648.html)）`iceandfire:icedragon`

**孵化获得（主要途径）**
- [冰龙](https://www.mcmod.cn/item/72648.html)蛋**只有被放置在平静的水中**才能孵化，孵化开始后会把水转换为冰封[龙蛋](https://www.mcmod.cn/item/192989.html)。
- 孵化耗时 **6 分钟**，完成后冰会破碎。
- 孵化出的小龙**只会将放置这颗[龙蛋](https://www.mcmod.cn/item/192989.html)的玩家视作主人**。

**繁殖（用于产蛋）**
- 对**不同性别的同属性龙**使用对应繁殖食物：[冰龙](https://www.mcmod.cn/item/72648.html)用**花霜酱**，可使其进入繁殖状态，**对生长阶段无要求**。
- 繁殖后在母龙附近生成由龙巢、草径/土径和[龙蛋](https://www.mcmod.cn/item/192989.html)组成的结构，两龙进入 **300 秒繁殖冷却**。
- ⚠️ 此[龙蛋](https://www.mcmod.cn/item/192989.html)非玩家放置，直接孵化出的小龙将处于**无主状态**。

---

## 二、[火龙](https://www.mcmod.cn/item/72647.html)（[Fire Dragon](https://www.mcmod.cn/item/72647.html)）`iceandfire:firedragon`

**孵化获得（生存模式主要途径）**
- [火龙](https://www.mcmod.cn/item/72647.html)蛋**仅在被放置在火焰中**才能孵化，孵化出的小龙只会将放置[龙蛋](https://www.mcmod.cn/item/192989.html)的玩家视作主人。
- 孵化耗时 **6 分钟**，完成时火焰会熄灭。
- ⚠️ 火必须**全程保持燃烧**才能完成孵化，建议使用**下界岩**之类的方块维持燃烧。

**繁殖**
- [火龙](https://www.mcmod.cn/item/72647.html)对应繁殖食物为**花火汤**，其余规则同[冰龙](https://www.mcmod.cn/item/72648.html)（不限生长阶段、300 秒冷却、产下的蛋孵化出无主小龙）。

**直接驯服**
- 仅**创造模式[龙食](https://www.mcmod.cn/item/62073.html)**可直接驯服野生[火龙](https://www.mcmod.cn/item/72647.html)（生存模式不可）。

---

## 三、[电龙](https://www.mcmod.cn/item/790978.html)（[Lightning Dragon](https://www.mcmod.cn/item/790978.html)）`iceandfire:lightning_dragon`

**孵化获得**
- [电龙](https://www.mcmod.cn/item/790978.html)蛋**仅会在暴露于主世界雨天/雷雨天的环境下**才能孵化，孵化出的小龙只会将放置[龙蛋](https://www.mcmod.cn/item/192989.html)的玩家视作主人。
- 孵化耗时 **6 分钟**，完成时会**降下一道闪电**（有可能生成火焰对幼龙造成伤害）。

**繁殖**
- [电龙](https://www.mcmod.cn/item/790978.html)对应繁殖食物为**花电浆**，其余规则同上。

---

## 四、[龙蛋](https://www.mcmod.cn/item/192989.html)（[Dragon Egg](https://www.mcmod.cn/item/192989.html)）`iceandfire:dragonegg`

- 分为**[冰龙](https://www.mcmod.cn/item/72648.html)蛋**和**[火龙](https://www.mcmod.cn/item/72647.html)蛋**（页面另列[电龙](https://www.mcmod.cn/item/790978.html)蛋）。
- [冰龙](https://www.mcmod.cn/item/72648.html)蛋有四种颜色：冰蓝、宝蓝、雪白、纹银；**新版[冰龙](https://www.mcmod.cn/item/72648.html)蛋在水中结冰 6 分钟后**孵化出对应颜色的小[冰龙](https://www.mcmod.cn/item/72648.html)。
- [火龙](https://www.mcmod.cn/item/72647.html)蛋有四种颜色：赤红、翠绿、铜黄、暗灰；**新版[火龙](https://www.mcmod.cn/item/72647.html)蛋在火焰中旋转 6 分钟后**孵化出对应颜色的小[火龙](https://www.mcmod.cn/item/72647.html)。

---

## 五、驯服之后能做什么（针对已驯服的龙）

以下内容来自「驯龙通用」章节，**仅适用于已经属于你的龙**：

- **乘骑**：主手空手右键驯服的龙即可骑乘（**不需要也不能装备鞍**）；按 **G 键**发动近战攻击，**Shift** 下龙。
- **面板**：主手空手**潜行右键**驯龙可打开面板，显示名字、血量、生长阶段、饥饿值、性别、所属主人；打开后可装备**龙铠和旗帜**。
- **可用道具**：
  - **[驯龙杖](https://www.mcmod.cn/item/62080.html)**（`iceandfire:dragon_stick`）：右键用[龙蛋](https://www.mcmod.cn/item/192989.html)孵出的龙，可让龙**坐下、起身或跟随**；潜行右击可把龙的**家设置为当前坐标**。
  - **龙歌长笛**（`iceandfire:dragon_flute`）：把附近飞在空中的驯龙唤回地面，**对野龙无效**。
  - **龙吟号角**（`iceandfire:dragon_horn`）：右击把龙装进号角，之后蓄力放出；号角会显示龙的种类、年龄、性别和名字，并保持装入时的饥饿与生长阶段。
  - **[龙食](https://www.mcmod.cn/item/62073.html)**（`iceandfire:dragon_meal`）：普通[龙食](https://www.mcmod.cn/item/62073.html)每次加速龙一天的生长；**萎靡[龙食](https://www.mcmod.cn/item/62073.html)**（`iceandfire:sickly_dragon_meal`）可永久暂停生长；**创造模式[龙食](https://www.mcmod.cn/item/62073.html)**（`iceandfire:creative_dragon_meal`）可直接驯服野生龙。
  - **唤龙晶石**（火/冰/电）：右击绑定一条对应属性的驯龙，之后可快速传送（要求龙所在区块刷新，且龙不在号角中、与召唤者同维度）。

**[龙食](https://www.mcmod.cn/item/62073.html)配方**（JAR 解析，`iceandfire:dragon_meal` ×1）：

```
B M B
M B M
B M B
```
- `B` = `minecraft:bone` ×5
- `M` = 标签 `#iceandfire:dragon_food_meat` ×4

---

## 六、知识库未覆盖的部分

- [冰龙](https://www.mcmod.cn/item/72648.html)、[电龙](https://www.mcmod.cn/item/790978.html)**是否有生存模式直接驯服手段**，本地知识库未给出（[火龙](https://www.mcmod.cn/item/72647.html)页面明确说生存模式只能靠孵化）。
- 各龙的**注册名**在本次检索中未提供（`iceandfire:icedragon` 等为实体解析结果，非页面原文）。
- 如需更详细的信息，请尝试更具体地描述你的问题，例如指定模组名称或询问特定方面（驯服、繁殖、掉落物等）。

---

## IAF-M02-T01 — 冰火传说里的龙心怎么获得？

规则：True；裁判：pass。

## 冰火传说：龙心（Dragon Heart）获取方式

**核心答案：龙心不是直接击杀掉落，而是击杀飞龙后对「龙尸」进行搜刮（右键）获得，且固定在搜刮的最后一轮掉落。**

### 三种龙心

| 中文名 | 注册名 | 来源模组 |
|---|---|---|
| [冰龙心脏](https://www.mcmod.cn/item/62077.html) | `iceandfire:ice_dragon_heart` | iceandfire |
| [火龙心脏](https://www.mcmod.cn/item/62076.html) | `iceandfire:fire_dragon_heart` | iceandfire |
| [电龙心脏](https://www.mcmod.cn/item/799029.html) | `iceandfire:lightning_dragon_heart` | iceandfire |

三者均为**材料**类物品，最大堆叠 **64 个/组**。

### 获取流程（以[火龙](https://www.mcmod.cn/item/72647.html)为例，[冰龙](https://www.mcmod.cn/item/72648.html)/[电龙](https://www.mcmod.cn/item/790978.html)机制一致）

1. **击杀飞龙** → 龙死亡后**转化为龙尸**（不是直接掉物品）。
2. **空手右键龙尸**进行搜刮，可获得龙鳞、龙肉、龙心、龙蛋等战利品。
3. **龙心固定在搜刮的最后一轮掉落**，每次只掉 1 个。
4. 搜刮若干次后，**龙尸会转化为骨架**，此后搜刮只能获得龙骨和飞龙颅骨（颅骨只在最后一次掉落）。

### 关键限制与注意事项

- **生长阶段决定是否有龙尸**：生长 4 天及更少的龙死亡后**没有龙尸**；9 天以内只有骨架（只能获取一颗飞龙颅骨）；**生长 10 天及以上才有完整龙尸**，才能搜刮到龙心。
- **不要用玻璃瓶**：手持空瓶右键龙尸会改为获取一瓶龙血（消耗空瓶），**此操作会覆盖上述规则，影响龙鳞、龙心、龙肉和龙蛋的获取**。想拿龙心请务必**空手**搜刮。
- **搜刮次数 N 随龙的阶段增加**：1–5 阶分别为 1、3、7、12–13、20 次（[火龙](https://www.mcmod.cn/item/72647.html)数据）。
- [冰龙](https://www.mcmod.cn/item/72648.html)、[电龙](https://www.mcmod.cn/item/790978.html)的描述同样写明「**龙心固定在搜刮的最后一轮掉落**」，每只龙固定获得一个。

### 龙心的用途

- **[冰龙心脏](https://www.mcmod.cn/item/62077.html)**：合成**龙息锻炉核心**；可代替旧版[冰龙](https://www.mcmod.cn/item/72648.html)头骨合成[冰龙](https://www.mcmod.cn/item/72648.html)全身旗帜图案。
- **[火龙心脏](https://www.mcmod.cn/item/62076.html)**：合成**龙钢锻炉核心**；1.8.0 版本以后可代替旧版[火龙](https://www.mcmod.cn/item/72647.html)头骨合成[火龙](https://www.mcmod.cn/item/72647.html)全身旗帜图案。
- **[电龙心脏](https://www.mcmod.cn/item/799029.html)**：合成**龙霆锻炉核心**；也可用于合成「[电龙](https://www.mcmod.cn/item/790978.html)纹章」旗帜图案。

### 补充说明

- 关于「[火龙](https://www.mcmod.cn/item/72647.html)心转化为龙之心」：资料提到若安装了**龙之研究**等多模组环境，部分整合允许将[火龙](https://www.mcmod.cn/item/72647.html)心直接转化为龙之心，但**在 1.12.2 版本中无效**。
- 以上机制来自 Ice And Fire Community Edition 的百科资料（来源：https://www.mcmod.cn/item/62076.html 、https://www.mcmod.cn/item/62077.html 、https://www.mcmod.cn/item/799029.html 、https://www.mcmod.cn/item/72647.html 、https://www.mcmod.cn/item/72648.html 、https://www.mcmod.cn/item/790978.html ）。当前安装版本为 iceandfire 2.0-beta.17 / Minecraft 1.21.1 / NeoForge，具体数值若与移植版有差异，请以实际 JAR 为准。

如需更详细的信息，请尝试更具体地描述你的问题，例如指定模组名称或询问特定方面（驯服、繁殖、掉落物等）。

---

## IAF-A01-T01 — 冰火传说里怎么驯服野生的龙？

规则：True；裁判：pass。

## 冰火传说（Ice And Fire）驯服野生龙的方法

**核心结论：生存模式下，野生龙基本无法直接驯服，唯一正规途径是孵化[龙蛋](https://www.mcmod.cn/item/192989.html)。** 下面按证据分别说明。

---

### 一、野生龙能否直接驯服？

- **[火龙](https://www.mcmod.cn/item/72647.html)**：证据明确指出——**野生[火龙](https://www.mcmod.cn/item/72647.html)只能使用创造模式专属的「创造模式[龙食](https://www.mcmod.cn/item/62073.html)」驯服**，生存模式下只能通过孵化[龙蛋](https://www.mcmod.cn/item/192989.html)获得驯服的[火龙](https://www.mcmod.cn/item/72647.html)。（该页还提到一个例外：经测试「深渊国度的塞壬之歌卷轴」可以驯服，但这属于跨模组联动，非本模组常规手段。）
- **[冰龙](https://www.mcmod.cn/item/72648.html) / [电龙](https://www.mcmod.cn/item/790978.html)**：检索到的驯龙通用页只说明「**创造模式[龙食](https://www.mcmod.cn/item/62073.html)可以直接驯服野生龙**」，未提供生存模式直接驯服野生[冰龙](https://www.mcmod.cn/item/72648.html)/[电龙](https://www.mcmod.cn/item/790978.html)的方法。
- **[龙食](https://www.mcmod.cn/item/62073.html)（`iceandfire:dragon_meal`）**：普通[龙食](https://www.mcmod.cn/item/62073.html)用于喂食**已驯服**的[火龙](https://www.mcmod.cn/item/72647.html)和[冰龙](https://www.mcmod.cn/item/72648.html)（**野生的不行**），每次喂食会让龙慢慢变大、阶段提升。

> ⚠️ 注意：[驯龙杖](https://www.mcmod.cn/item/62080.html)、龙歌长笛、龙吟号角、唤龙晶石等工具，证据中均描述为对**已驯服的龙**使用（如唤回、装龙、传送、设置家），**不是驯服野生龙的手段**。

---

### 二、正规途径：孵化[龙蛋](https://www.mcmod.cn/item/192989.html)获得驯服龙

孵化出的幼龙**会且只会将放置这颗[龙蛋](https://www.mcmod.cn/item/192989.html)的玩家视作主人**，即自动成为你的驯服龙。孵化均需 **6 分钟**。

| 龙种 | 注册名 | 孵化条件 |
|---|---|---|
| **[冰龙](https://www.mcmod.cn/item/72648.html)** | `iceandfire:icedragon` | [龙蛋](https://www.mcmod.cn/item/192989.html)放置在**平静的水中**，孵化时水会转为冰封[龙蛋](https://www.mcmod.cn/item/192989.html)，完成后冰破碎 |
| **[火龙](https://www.mcmod.cn/item/72647.html)** | `iceandfire:firedragon` | [龙蛋](https://www.mcmod.cn/item/192989.html)放置在**火焰中**，需火焰持续燃烧（建议用下界岩等保持燃烧），完成时火焰熄灭 |
| **[电龙](https://www.mcmod.cn/item/790978.html)** | `iceandfire:lightning_dragon` | [龙蛋](https://www.mcmod.cn/item/192989.html)暴露在**主世界雨天/雷雨天**环境下，完成时会降下一道闪电（可能生成火焰伤害幼龙） |

**[龙蛋](https://www.mcmod.cn/item/192989.html)（`iceandfire:dragonegg`）颜色**：
- [冰龙](https://www.mcmod.cn/item/72648.html)蛋：冰蓝、宝蓝、雪白、纹银
- [火龙](https://www.mcmod.cn/item/72647.html)蛋：赤红、翠绿、铜黄、暗灰

---

### 三、[龙蛋](https://www.mcmod.cn/item/192989.html)怎么来？——繁殖获得

- 对**不同性别的同属性龙**使用对应的繁殖食物，可使其进入繁殖状态（**对生长阶段无要求**）：
  - [火龙](https://www.mcmod.cn/item/72647.html) → **花火汤**
  - [冰龙](https://www.mcmod.cn/item/72648.html) → **花霜酱**
  - [电龙](https://www.mcmod.cn/item/790978.html) → **花电浆**
- 繁殖后会在母龙附近生成由**龙巢、草径/土径和[龙蛋](https://www.mcmod.cn/item/192989.html)**组成的结构，两龙进入 **300 秒繁殖冷却**。
- ⚠️ **此[龙蛋](https://www.mcmod.cn/item/192989.html)非玩家放置，直接孵化出的小龙将处于无主状态**——所以想获得「有主」的龙，需要自己把[龙蛋](https://www.mcmod.cn/item/192989.html)捡起后重新放置孵化。

---

### 四、驯服后的操作（供参考）

- **乘骑**：主手空手右键驯服的龙（不需要也不能装备鞍），G 键近战攻击，Shift 下龙。
- **面板**：主手空手潜行右键驯龙，可查看名字、血量、生长阶段、饥饿值、性别、主人，并可装备龙铠和旗帜。
- **可用道具**：[驯龙杖](https://www.mcmod.cn/item/62080.html)、龙歌长笛、龙吟号角、[龙食](https://www.mcmod.cn/item/62073.html)、对应属性的唤龙晶石。

---

### 五、补充：[龙食](https://www.mcmod.cn/item/62073.html)配方（JAR 解析）

**[龙食](https://www.mcmod.cn/item/62073.html)（`iceandfire:dragon_meal`）× 1**，工作台 3×3：

```
B M B
M B M
B M B
```

- **B** = `minecraft:bone`（骨头）× 5
- **M** = 标签 `#iceandfire:dragon_food_meat`（[龙食](https://www.mcmod.cn/item/62073.html)肉类）× 4

> 该配方仅说明加工关系，不能据此推断材料的初次获取途径。

---

**一句话总结**：生存模式想驯服野生龙基本不可行，正确路线是「繁殖得蛋 → 按属性条件孵化 → 幼龙自动认主」。创造模式[龙食](https://www.mcmod.cn/item/62073.html)可直接驯服野生龙（[火龙](https://www.mcmod.cn/item/72647.html)已明确，[冰龙](https://www.mcmod.cn/item/72648.html)/[电龙](https://www.mcmod.cn/item/790978.html)证据仅笼统提及）。

如需更详细的信息，请尝试更具体地描述你的问题，例如指定模组名称或询问特定方面（驯服、繁殖、掉落物等）。

---

## IS-M03-T01 — Iron's Spellbooks 的墨水是怎么制作的？

规则：True；裁判：pass。

## Iron's Spellbooks 墨水制作方法

**先纠正一个前提**：检索到的资料中，**没有**"墨水在工作台合成"的配方。墨水的获取途径是**[炼金锅](https://www.mcmod.cn/item/715507.html)（[Alchemist Cauldron](https://www.mcmod.cn/item/715507.html)）**，而不是普通工作台。

---

### 一、核心方法：用[炼金锅](https://www.mcmod.cn/item/715507.html)把[法术卷轴](https://www.mcmod.cn/item/699058.html)还原成墨水

来自 [法术卷轴页面](https://www.mcmod.cn/item/699058.html) 的"还原为墨水"章节：

- [炼金锅](https://www.mcmod.cn/item/715507.html)**有水位**、且未修改配置文件时，把**[法术卷轴](https://www.mcmod.cn/item/699058.html)**放入**材料位**；
- 卷轴被消耗，有 **50% 概率**生成**对应品质**的墨水；
- 墨水生成需要**占用一个药水位**；
- 成功时液体颜色变浓，需用**玻璃瓶**取出。

也就是说：**卷轴品质 → 墨水品质**（普通卷轴出[普通墨水](https://www.mcmod.cn/item/699064.html)，传说卷轴出传说墨水）。

### 二、[炼金锅](https://www.mcmod.cn/item/715507.html)本身的合成（工作台）

来自 JAR 解析的配方（`irons_spellbooks:alchemist_cauldron` × 1）：

```
I I
ICI
EFE
```

- `I` = 标签 `#c:ingots/iron` × 4（铁锭）
- `C` = `minecraft:cauldron` × 1（[炼金锅](https://www.mcmod.cn/item/715507.html)底座）
- `E` = `irons_spellbooks:arcane_essence` × 2（奥术源质）
- `F` = `irons_spellbooks:cinder_essence` × 1（烈焰精华）

### 三、[炼金锅](https://www.mcmod.cn/item/715507.html)的其他墨水相关功能

来自 [炼金锅页面](https://www.mcmod.cn/item/715507.html)：

- **提升墨水品质**：[炼金锅](https://www.mcmod.cn/item/715507.html)可用于把低品质墨水升级为高品质墨水（页面列有该章节，但检索片段未给出具体材料，**本地知识库未覆盖该步骤的详细配方**）。
- **放置墨水**：使用墨水会占用一个药水位；每个药水位可存一瓶墨水。
- **JAR 配方补充**：存在一条 `irons_spellbooks:alchemist_cauldron_empty` 类型配方——**玻璃瓶 + 250mB [普通墨水](https://www.mcmod.cn/item/699064.html)流体 → 1 个 `irons_spellbooks:common_ink`**，即用玻璃瓶从锅中取出墨水。

### 四、墨水的其他获取方式

来自 [普通墨水页面](https://www.mcmod.cn/item/699064.html)：

- **战利品箱**：各类战利品箱中均可找到。
- **流浪商人**：2.0.1 版本可能以约 3 个奥术源质收购[普通墨水](https://www.mcmod.cn/item/699064.html)；3.0.0 版本后可能直接出售[普通墨水](https://www.mcmod.cn/item/699064.html)。

### 五、墨水的用途（补充）

- 在**[卷轴撰写台](https://www.mcmod.cn/item/699147.html)（[Scroll Forge](https://www.mcmod.cn/item/699147.html)）**中撰写[法术卷轴](https://www.mcmod.cn/item/699058.html)：墨水品质决定卷轴品质，写出的法术等级为该品质中**最低**等级；还需放入**纸**（每张卷轴消耗一张）和决定流派的"标志性制作素材"。
- 3.3.0 之后可在**奥术铁砧**上用墨水把已有卷轴的法术升级（如 1 级普通冰锥 + 平凡墨水 → 2 级普通冰锥）。

---

### 未覆盖的部分

- [炼金锅](https://www.mcmod.cn/item/715507.html)"**提升墨水品质**"的具体材料与步骤，检索片段未给出，**本地知识库未包含该细节**。
- 各品质墨水（`irons_spellbooks:uncommon_ink`、`rare_ink`、`epic_ink`、`legendary_ink`）除卷轴还原外的独立制作配方，检索数据中未出现。

如需更详细的信息，请尝试更具体地描述你的问题，例如指定模组名称或询问特定方面（驯服、繁殖、掉落物等）。

---
