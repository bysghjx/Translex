# 段落合 + wrap 重排 实现计划

## 核心思路
段落合翻译保留（连贯性好），但渲染时不按 AI 的 \n 拆行——
段落译文渲染成一个完整 Component（StyleCodec.reapply），Font.split 按 tooltip 宽度重新换行，
动态调 wrapWidth 直到行数 = 原段落行数，再 1:1 替换。

## 改动文件

### 1. LineTemplate.java - 新增 buildParagraphComponent
- `buildParagraphComponent(String translatedParagraph)`: fillNumbers + 清残留占位符 + **\n -> 空格**（合并单段）+ StyleCodec.reapply(整段, styleMap) -> 返回一个完整 Component
- 不再按 \n 拆行（删除 splitParagraphTemplates 的依赖，或保留但段落路径改用 buildParagraphComponent）

### 2. ItemTranslationPipeline.java - 段落路径改用整段 Component
- 段落 future/缓存命中时：`Component paraComponent = paraTmpl.buildParagraphComponent(translated)`
- storedTemplates[段落首行] = 整段译文模板（\n 已换空格），其余段落行 = 特殊标记（如空串）
- result[段落首行] = paraComponent，其余段落行 = null（渲染时由 Mixin wrap 填充）

### 3. ScreenTooltipMixin.java + DrawContextTooltipMixin.java - 段落行 wrap 重排
- 单行：原逻辑（LineTemplate.fromText(original).buildText(replacement)）
- 段落行（replacement.get(i) 含整段，replacement.get(i+1..) 为空标记）：
  1. 把整段模板渲染成 Component（用原始段落行的 styleMap）
  2. Font.split(component, wrapWidth) 换行
  3. 动态调 wrapWidth：行数 > 原段落行数 -> 加宽；行数 < 原段落行数 -> 减宽
  4. 行数匹配后，逐行替换原段落行的 tooltip 组件
- 需要 Font 实例（DrawContextTooltipMixin 有；ScreenTooltipMixin 用 Minecraft.getInstance().font）

### 4. handleOutputMode / 缓存 - 段落存整段
- 段落行存到缓存时：首行存整段模板，其余行存空标记
- 缓存命中时：识别段落首行（非空）+ 空标记行，重建整段

## 风险
- Font.split 签名需确认（Font.split(Component, int) -> List<FormattedCharSequence>）
- 动态调 wrapWidth 可能不收敛（极短/极长文本）-> 加超时/回退
- Mixin 改 List 结构（插入/删除行）复杂
- 段落行的 styleMap：需要原始段落行的 styleMap（从原 tooltip 提取），存哪？

## 简化方案（如果完整方案太复杂）
段落译文 \n -> 空格，reapply 成 Component，Font.split 换行。
如果 wrap 行数 = 原段落行数，逐行替换；否则回退原文（不调 wrapWidth）。
这样颜色准（整段 reapply），行数靠运气（回退保护）。
