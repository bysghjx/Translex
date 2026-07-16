# Translex 后续工作交接（HANDOFF）

> 本文档由前序会话生成，用于交接剩余工作。最后更新对应提交 `b3688b6`。

## 项目背景速览

- **Translex**：Hypixel SkyBlock 翻译 mod，纯 Java + Fabric。
- 两个 MC 版本并行维护：
  - `master` 分支 = **1.21.11 / Yarn**（主开发线，所有新功能先在这做）
  - `mc/26.1.2` 分支 = **26.1.2 / Mojang 映射 + non-remap Loom**（已完成版本迁移基线，提交 `43a4ec2`）
  - `feature/pipeline-cache-prompt` 分支（当前）= 基于 master 的功能开发线，含下述 3 个提交，**尚未合并回 master**
- 构建：`./gradlew build`，产物 `build/libs/Translex-1.6.10-SNAPSHOT.jar`。JDK 用 `C:/Users/Administrator/.jdks/ms-21.0.7`（1.21.11）。
- 所有文件操作用**完整 Windows 绝对路径 + 反斜杠**（见 CLAUDE.md，有路径 bug 需规避）。
- 调 git 前看 CLAUDE.md：提交信息结尾加 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`；不直接提交到 master，用 feature 分支。

## 已完成（feature/pipeline-cache-prompt，3 个提交）

1. **`bd70274` 双管线分离 + 缓存串台修复 + prompt重构 + 多语言**
   - 翻译管线拆成 `ChatTranslationPipeline` / `ItemTranslationPipeline`（`translate/pipeline/` 包），各自独立 dispatcher/缓存/prompt/进度行，共享无状态 `TranslationRequester`。`TranslationManager` 降级为门面转发。
   - 物品缓存键改为 `itemId#loreHash`（`TooltipKeyUtil`），修复同 ID 不同 lore 串台。关 GUI 清 temp 缓存。
   - prompt 三层分离：强制 system prompt（`TranslationPrompts`，用户不可改）+ 可选 user prompt（`userChatPrompt`/`userItemPrompt`，空则不发）+ `targetLanguage` 多语言（#8）。
2. **`cadccf4` 样式渲染修复 + prompt/payload优化 + 专有名词三档**
   - 修物品翻译变白（多轮：预翻译行补 `<s0>` 标签、缓存命中取译文模板、缺 tag 回退已有样式、空行不包标签）。
   - 物品 prompt 强化（标签不可合并/占位符不可填值）。专有名词三档可选（`properNounMode`: keep/translate/item_only，Web 下拉）。
   - trace 记录完整 messages，抓包页可滚动看完整 prompt。
3. **`b3688b6` #3 临时tooltip Slot绑定 + #5 物品选取用Slot**
   - `HandledScreenAccessor`（读 focusedSlot）+ `HoverSlotTracker`（激活态状态机）。
   - temporary 译文只绑定按 P 时的槽位，移开显原文、移回需重按 P；permanent 不受门控。

**全部已在 1.21.11 实机测试通过**（含性能：F3 帧率 368+ FPS 无卡顿）。

## 剩余工作（按原始 9 条需求 + 衍生项）

### 主线功能（用户最初 9 条里未做的）
- **#6 Web 页面重构**：把单页配置重构成多个子标签页；debug 模式加更多功能。工作量大、偏前端（`src/main/resources/assets/translex/web/index.html`，Vue3+Tailwind CDN，单文件）。
- **#7 AI 供应商适配器**：当前硬编码 OpenAI 兼容格式（`messages`/`choices`，`TranslationRequester`）。要做可扩展适配器框架，内置 OpenAI + Anthropic（Anthropic 原生格式不同：system 独立字段、`content[].text`、`x-api-key` 头、`/v1/messages` 端点）+ 多预设。用户原话"可拓展的吧，然后再加上多预设设计"。
- **#9 中译英发送命令**：用户已定方案 = 新增 `/translex say <中文>` 独立命令，把输入译成英文并**自动发送到聊天**（与"看别人消息译成中文"反向，互不干扰）。靠现有翻译管线 + 一个固定 targetLanguage=English 的临时请求实现。

### 衍生/技术债
- **#2 缓存系统受控**：文本 shard 缓存（`TranslationCacheManager`/`TranslationCache`）目前超 1 万条粗暴全清，无 LRU/TTL。可加上限+淘汰策略。（部分已做：物品 temp 缓存已加关 GUI 清理）
- **移植到 26.1.2**：以上所有 feature 提交都只在 `master` 线（1.21.11）。`mc/26.1.2` 分支还停在迁移基线 `43a4ec2`，需把双管线/缓存键/prompt/Slot 等成果移植过去。纯逻辑类（TooltipKeyUtil/PipelineConfig/BatchDispatcher/两个Pipeline/HoverSlotTracker/TranslationPrompts）大部分可直接搬；Mixin（ChatHud→ChatComponent、DrawContext→GuiGraphicsExtractor、HandledScreen、tooltip 组件）需按 26.x Mojang API 调整。详见记忆 `translex-26-1-2-bump.md`。
- **合并 feature 分支**：`feature/pipeline-cache-prompt` 的 3 个提交最终需合并回 `master`（用户尚未要求，等其确认）。

## 关键架构记忆（避免踩坑）
- **缓存键算法唯一来源** = `TooltipKeyUtil`（util 包）：`itemId#loreHash`，loreHash = 对 tooltip 第 1 行起（跳物品名）规范化后 SHA-256 前 16 hex。temp/permanent/两个 Mixin/ClientStateManager 必须全走它，否则键不一致 → 永远 miss。
- **物品翻译用 `LineTemplate`**（不是 splitter）：每行抽样式成 `<sN>` 标签 + 数字成 `{N}` 占位符，发给 AI，回来 `buildText` 重建。变白问题都源于样式标签/styleMap 不匹配。
- **聊天管线不走持久缓存、不做 number normalize**（`{num}` 占位符曾泄漏，已修）。
- **物品翻译是批处理**：1500ms 窗口收集多行 → 一个字典 JSON 请求，不是逐行发。
- **temporary Slot 门控只对 temporary**，permanent/chat 不门控。
- 诊断日志 `[StyleCodec] 样式回退(白色)` 在 debug 模式输出（已节流，每 2s），用于定位变白。

## 验证方式
进游戏（1.21.11 + Fabric + Fabric API + fabric-language-kotlin），`/translex config` 开 Web 面板，`/translex debug` 看抓包页。物品翻译默认按 P 键。
