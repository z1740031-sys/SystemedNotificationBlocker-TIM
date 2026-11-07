# 屏蔽通知（TIM 适配版）

---

## ⚠️ 声明

**本仓库是我个人为自己的 TIM 设备做的最小适配分支，仅供自用，不是官方项目，也不代表原作者立场。**

本项目的全部代码基于 **[xjyzs/SystemedNotificationBlocker](https://github.com/xjyzs/SystemedNotificationBlocker)**（v1.5.1）修改而来。
**原始设计与实现的全部著作权归原作者 `xjyzs` 所有。** 本项目未声称任何开源许可证。

本项目**不提供任何担保**，**请勿用于商业用途**，也不建议日常使用（TIM 与 QQ 的行为差异可能导致通知屏蔽不完整）。

> **原作者 `xjyzs` 如有任何异议，可随时通过 [Issue](https://github.com/z1740031-sys/SystemedNotificationBlocker-TIM/issues)
> 或 GitHub 私信联系我，我会立即删除本仓库、无需任何说明。**

---

## 与上游的差异

本分支只做最小必要改动，用于在装有 TIM（`com.tencent.tim`）的设备上工作：

| 改动 | 位置 | 说明 |
|---|---|---|
| 目标包名扩展 | `MainHook.kt` | 拦截条件由仅 `com.tencent.mobileqq` 扩展为 `com.tencent.mobileqq` **或** `com.tencent.tim` |
| 应用显示名 | `res/values/strings.xml` | `app_name` 改为「屏蔽通知」，便于与上游版本区分 |
| 移除上游发布工作流 | `.github/workflows/release-to-xposed.yml` | 该工作流需上游的 `XPOSED_REPO_TOKEN`，私有分支无此凭据 |

**包名与版本号保持不变**（`io.github.xjyzs.systemednotificationblocker`，versionCode `7` / versionName `1.5.1`），
以便在已安装官方版本 / 核心破解环境下直接覆盖安装。

## 功能（继承自上游 v1.5.1）

- 屏蔽 QQ / TIM 群聊的 **@全体成员** 消息（支持旧式 `[有全体消息]` 与新式 `[@全体成员]` 等多种形态）
- 移除通知栏 **@全体成员** 前缀（保留正文）
- 在通知栏保留所有消息，包括**已撤回**消息，防止旧消息被新消息覆盖
- 屏蔽 QQ / TIM **群公告**通知
- 屏蔽 QQ / TIM **群待办**通知
- 黑白名单：按群聊名称 / 关键词过滤

## 实现原理

Hook 系统框架 `com.android.server.notification.NotificationManagerService` 的
`enqueueNotificationInternal`。

由于 Android 17 上该方法存在多个重载且参数位置不固定，模块在运行时：

1. 遍历所有重载并记录签名到日志；
2. **按运行时类型**定位 `Notification` 对象（而非硬编码 `args[6]`）；
3. 按消息来源包名从 `args` 中匹配，兼容 QQ 与 TIM；
4. 按各重载的**真实返回类型**（`boolean` / `int` / `long` / `void`）决定拦截时的返回值，避免框架拆箱 `null` 崩溃。

因只 Hook 系统框架，目标应用难以察觉。

## 通知文案的兼容处理

QQ/TIM 的「@全体成员」在不同版本中会落在不同的 `Bundle` 字段里，模块会依次搜集
`EXTRA_TEXT`、`EXTRA_BIG_TEXT`、`EXTRA_SUB_TEXT`、`EXTRA_SUMMARY_TEXT`、`EXTRA_TEXT_LINES`，
再挑选最匹配的一条进行处理；改写时也会写回**原本所在的字段**。

## 构建

环境要求：JDK 21+、Android SDK（`compileSdk 37`）。

```bash
# 指向你的 SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 本机是 arm64 且 Android 15+：
./gradlew assembleArm64Minsdk35Release

# 其他机型可选：
#   assembleArm64Minsdk29Release   (Android 10+)
#   assembleArm64Minsdk26Release   (Android 8.0+)
#   assembleUniversalRelease       (不确定/模拟器)
```

产物位于 `app/build/outputs/apk/<flavor>/release/`。

> 未配置 `keystore.jks` 与 `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 时，
> 会自动回退为 **debug 签名**。已在 CI 中按此方式产出可安装包。

## 使用方法

1. 安装 APK；
2. 在 LSPosed 中启用本模块，作用域勾选**系统框架**（`android` / `system`）；
3. 打开本模块配置屏蔽规则；
4. **重启**设备生效。

## 日志

日志写在设备路径 `/data/system/SystemedNotificationBlockerLogs.txt`（需 root 读取）：

```bash
su -c cat /data/system/SystemedNotificationBlockerLogs.txt
```

开启「移除 @所有人 前缀」后可在日志中看到命中的通知明细（标题 / 内容 / 通道）。

## 已知限制

- 仅针对 **QQ 与 TIM**；上游的微信（`com.tencent.mm`）相关逻辑在 v1.5.1 重构中已移除。
- 需要 LSPosed 作用域包含系统框架。模块对系统进程的 Hook 依赖设备 ROM 的
  `NotificationManagerService` 重载形态，换 ROM 后建议先看日志确认是否命中。

## 致谢

全部原始设计与实现归 **xjyzs** 所有：
<https://github.com/xjyzs/SystemedNotificationBlocker>