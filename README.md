# Translex

一个面向 Minecraft Fabric 客户端的 AI 翻译 Mod。

Translex 可以翻译聊天消息、物品名称和 Lore，尽量保留 Minecraft 原有的颜色与文本样式，同时保护数字、百分比、价格和附魔等级等内容不被 AI 改写。

## 功能

- 翻译聊天消息和任意文本。
- 翻译物品名称、多行 Lore 和 tooltip。
- 保留颜色、粗体、斜体等 Minecraft 文本样式。
- 保护数字、百分比、价格、附魔等级等关键内容。
- 支持 OpenAI-compatible API 和 Anthropic API。
- 支持运行时词典、附魔词汇和专有名词处理。
- 支持翻译缓存，减少重复请求。
- 支持将物品翻译永久保存，之后直接复用。
- 提供本地 WebUI，用于配置、连接测试和查看诊断信息。
- AI 返回异常时安全回退到原文。

## 支持环境

- Minecraft `26.1.2`
- Fabric Loader `0.19.3+`
- Fabric API `0.152.1+26.1.2`
- Fabric Language Kotlin `1.13.12+kotlin.2.4.0+`
- Java 25

Translex 是客户端 Mod，不需要安装到服务器。

## 安装

1. 安装对应版本的 Fabric 客户端。
2. 安装 Fabric API 和 Fabric Language Kotlin。
3. 将 Translex jar 放入 Minecraft 的 `mods` 文件夹。
4. 启动游戏。

## 配置

进入游戏后执行：

```text
/translex config
```

这会打开本地 WebUI。Translex 默认只监听本机地址 `127.0.0.1`，不会主动暴露到局域网。

首次使用需要配置：

- API Key
- API URL
- 模型名称
- AI Provider
- 目标语言

默认支持：

- OpenAI-compatible：`https://api.deepseek.com/chat/completions`
- Anthropic：`https://api.anthropic.com/v1/messages`

请勿把真实 API Key 提交到 Git 仓库、截图或公开日志中。

## 常用命令

```text
/translex config
```

打开配置 WebUI。

```text
/translex text <message>
```

翻译一段任意文本。

```text
/translex translate <message_id>
```

按聊天消息 ID 翻译消息。

```text
/translex say <message>
```

将文本翻译成英文后发送到聊天。

```text
/translex reload
```

重新加载磁盘上的配置。

```text
/translex mode <chat|temporary|permanent>
```

切换翻译结果的显示方式：

- `chat`：发送到聊天栏。
- `temporary`：临时显示。
- `permanent`：保存物品翻译，后续直接复用。

```text
/translex protocol <sN|TSP-FULL|TSP-HYBRID>
```

切换样式处理模式。默认使用 `TSP-HYBRID`，它通常比 Full 模式更省 token，也能减少颜色错位风险。

## 默认按键

- `P`：翻译当前鼠标悬停物品的 Lore。
- `Ctrl+P`：忽略缓存并强制重新翻译。

## 配置文件

配置目录：

```text
config/translex/
```

常见文件：

```text
config.toml                         主配置
cache/translation_cache.json        翻译缓存
cache/term_dict.json                运行时词典
item_cache.json                     物品永久翻译
metrics.json                        运行指标
```

## 许可证

本项目使用 [MIT License](LICENSE)。
