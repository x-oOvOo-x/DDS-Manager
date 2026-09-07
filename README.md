# DDS Manager

面向 Java 正版 Velocity 群组服的管理插件。

## 功能

- UUID 玩家档案、内置管理员、统一 `whitelist.json`、分服/服务器组白名单
- Last Server、Local/Global 聊天（Global 含服务器标签）、跨服 Tab、Join/Leave/Switch、`/seen`
- 原版三维度 Presence；无需新增后端 Mod，也不使用 RCON
- Tab 支持自定义服务器标签、可开关维度状态符号和原版 Ping 信号格
- Xaero/VoxelMap 世界标识同步
- 白名单分页、玩家详情、服务器状态等可点击聊天组件
- Hover 授权来源、点击添加/移除、危险操作单次确认
- JSON 原子写入与备份恢复、异步日志、管理审计

## Tab 显示

默认布局：

```text
⁑ [S]PlayerID   + 原版 Ping 信号格
```

维度符号位于头像后的显示名最前方，服务器标签与正版 ID 紧挨显示。维度统一使用一个可自定义符号，默认 `⁑`，不加 `[]`；符号颜色由 DDS 根据真实维度固定决定：主世界绿色、下界红色、末地淡紫色、未知灰色。

```json
"presence": {
  "showServerInTabName": true,
  "showDimensionInTabName": true,
  "dimensionSymbol": "⁑",
  "serverPrefixes": {
    "survival": "S",
    "creative": "C",
    "mirror": "M"
  },
  "serverLabels": {
    "survival": "<gradient:#55ff55:#00ffaa><bold>[S]</bold></gradient>"
  }
}
```

`dimensionSymbol` 支持 1–4 个可见字符，可改为 `▏`、`│`、`|`、`·` 或资源包字体 Glyph；颜色不可通过配置覆盖。关闭 `showDimensionInTabName` 后只隐藏维度符号，不影响服务器标签或 Ping。


## 服务器名称 / 标签格式

服务器标签有两层配置：

- `serverPrefixes`：简写回退值。例如 `"survival": "S"` 会自动显示为 `[S]`。
- `serverLabels`：完整标签模板。配置后直接替代 `[S]`，括号、符号、颜色和样式都由模板决定。

例如：

```json
"serverPrefixes": {
  "survival": "S",
  "creative": "C",
  "mirror": "M"
},
"serverLabels": {
  "survival": "<#55ff88><bold>[S]</bold></#55ff88>",
  "creative": "<gradient:#55ffff:#5555ff><bold>✦C✦</bold></gradient>",
  "mirror": "<light_purple><obfuscated>xx</obfuscated>M<obfuscated>xx</obfuscated></light_purple>"
}
```

DDS 使用自己的受限样式解析器，语法为 MiniMessage 风格，但不会加载 MiniMessage 运行库。可用格式：

| 类型 | 写法示例 |
| --- | --- |
| 命名颜色 | `<green>[S]</green>`、`<light_purple>[M]</light_purple>` |
| RGB | `<#55ff88>[S]</#55ff88>` 或 `<color:#55ff88>[S]</color>` |
| 粗体 | `<bold>[S]</bold>` 或 `<b>[S]</b>` |
| 斜体 | `<italic>[S]</italic>`、`<i>[S]</i>`、`<em>[S]</em>` |
| 下划线 | `<underlined>[S]</underlined>` 或 `<u>[S]</u>` |
| 删除线 | `<strikethrough>[S]</strikethrough>` 或 `<st>[S]</st>` |
| 混淆 | `<obfuscated>xx</obfuscated>` 或 `<obf>xx</obf>` |
| 渐变 | `<gradient:#55ffff:#5555ff>Creative</gradient>` |
| 彩虹 | `<rainbow>Mirror</rainbow>`；可写 `<rainbow:0.25>Mirror</rainbow>` 调整相位 |
| 过渡色 | `<transition:#55ff55:#5555ff:0.0>[S]</transition>`，相位范围 `-1`～`1` |
| 自定义字体 | `<font:minecraft:default>[S]</font>`；可配合资源包字体 Glyph |
| 重置 | `<reset>` |

支持的命名颜色：`black`、`dark_blue`、`dark_green`、`dark_aqua`、`dark_red`、`dark_purple`、`gold`、`gray`、`dark_gray`、`blue`、`green`、`aqua`、`red`、`light_purple`、`yellow`、`white`；`grey` / `dark_grey` 也可用。

需要直接显示 `<`、`>` 或 `\` 时可写 `\<`、`\>`、`\\`。标签模板最长 128 个 Unicode 码点。`<click>`、`<hover>`、`<insertion>`、未知标签、闭合错误等会被拒绝，避免通过配置注入交互事件。


最终 Tab 可得到类似：

```text
⁑ [S]PlayerID
⁑ ✦C✦PlayerID
```

## 管理员与权限

首次启动生成 `plugins/dds-manager/config.json`，停止代理后填写正版 UUID：

```json
{
  "administrators": [
    "00000000-0000-0000-0000-000000000000"
  ]
}
```

配置管理员与 `dds-manager.admin` 等价：可管理全部功能并绕过 DDS 白名单。普通玩家默认没有管理权限；`dds-manager.server.<server>` 和 `dds-manager.group.<group>` 只提供进服授权。若要委派管理，必须显式授予细粒度管理节点。

公开玩家命令包括 `/dds channel [local|global]` 和 `/dds seen <player>`。帮助和 Tab 补全会按权限过滤。

## 统一白名单文件

`plugins/dds-manager/whitelist.json` 可像 lls-manager 一样集中编辑全网白名单：

```json
{
  "whitelist": ["Alice", "Bob"]
}
```

首次启动会根据现有全网授权生成该文件。修改后执行 `/dds reload` 并点击一次 `[Confirm]` 即可同步：新增名字会成为 pending，首次正版登录后绑定 UUID；移除名字只撤销全网 scope，玩家的分服/服务器组授权仍保留。文件格式或玩家名无效时整次同步被拒绝，不会按残缺内容部分删除。命令修改全网白名单和快照导入后也会自动回写此文件。


## 常用管理命令

```text
/dds status
/dds server [server]
/dds player <player>
/dds reload
/dds alert <message...>
/dds whitelist status|on|off
/dds whitelist add|remove all <player...>
/dds whitelist add|remove server <server> <player...>
/dds whitelist add|remove group <group> <player...>
/dds whitelist list [page|player]
/dds whitelist check <player> <server>
/dds whitelist export|import [file]
/dds group create|delete <group>
/dds group add|remove <group> <server...>
/dds group list [group]
```

## 构建与许可

```bash
./gradlew clean build
```

项目按 GPL-3.0 分发。第三方许可与上游致谢见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 重大贡献者

[plusls](https://github.com/plusls) — [lls-manager](https://github.com/plusls/lls-manager) 的作者与 DDS Manager 的贡献者。DDS 的产品方向、跨服管理思路和最初需求源自 lls-manager。
