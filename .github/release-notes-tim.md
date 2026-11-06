## 屏蔽通知（TIM 适配版）v1.5.1

> ⚠️ **本仓库为个人自用、私自开发的私有分支，未经原作者任何授权。**
> 代码基于 **[xjyzs/SystemedNotificationBlocker](https://github.com/xjyzs/SystemedNotificationBlocker)**（v1.5.1）修改而来，
> **全部著作权归原作者 `xjyzs` 所有**，本项目不声明任何许可证，**严禁分发传播**。
> 如有侵权请联系我，我会立即删除本仓库。

### 安装包

- **`SystemedNotificationBlocker-TIM-v1.5.1.apk`** — arm64-v8a，minSdk 26 / targetSdk 37，versionCode 7，大小 2,331,646 字节
- SHA-256：`59862b30d8bc8dcc2c7b9b7bdf357f105fe94195257996b6e1a9355d2f250983`
- 签名：`ANDROIDK`（APK Signature Scheme v2 + v3）

### 与上游的差异

仅两处最小改动，用于在有 TIM 的设备上工作：

| 改动 | 位置 |
|---|---|
| 目标包名由仅 `com.tencent.mobileqq` 扩展为 **或** `com.tencent.tim` | `MainHook.kt` |
| 应用显示名改为「屏蔽通知」 | `strings.xml` |

包名与版本号保持不变（`io.github.xjyzs.systemednotificationblocker` / 7 / 1.5.1），可覆盖官方版本安装。

### 功能

- 屏蔽 QQ / TIM 群聊的 **@全体成员** 消息
- 移除通知栏 **@全体成员** 前缀（保留正文）
- 在通知栏保留所有消息，包括**已撤回**消息
- 屏蔽 **群公告** 通知
- 屏蔽 **群待办** 通知
- 黑白名单：按群聊名称 / 关键词过滤

### 安装

1. 安装 APK；
2. 在 LSPosed 中启用本模块，作用域勾选 **系统框架**（`android` / `system`）；
3. 打开模块配置屏蔽规则；
4. **重启设备**生效。

### 日志

```bash
su -c cat /data/system/SystemedNotificationBlockerLogs.txt
```

### 原理

Hook 系统框架 `com.android.server.notification.NotificationManagerService` 的
`enqueueNotificationInternal`。运行时会遍历所有重载、按运行时类型定位 `Notification`，
并按各重载真实返回类型决定拦截返回值，因此不依赖固定的参数下标。

只 Hook 系统框架，目标应用难以察觉。
