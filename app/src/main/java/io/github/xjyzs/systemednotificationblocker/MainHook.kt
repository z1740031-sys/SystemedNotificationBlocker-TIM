package io.github.xjyzs.systemednotificationblocker

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainHook : IXposedHookLoadPackage {

    @SuppressLint("PrivateApi")
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" || lpparam.packageName == "system") {
            val pref = XSharedPreferences(
                "io.github.xjyzs.systemednotificationblocker", "main"
            )
            pref.reload()

            val blacklistModeQQ = pref.getBoolean("blacklistModeQQ", false)
            val groupsQQSet = (pref.getString("groupsQQ", "") ?: "")
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val removePrefix = pref.getBoolean("removePrefix", false)
            val muteGroupTodo = pref.getBoolean("muteGroupTodo", true)
            val muteGroupAnnouncement = pref.getBoolean("muteGroupAnnouncement", true)

            try {
                val notificationManagerClass = Class.forName(
                    "com.android.server.notification.NotificationManagerService",
                    false,
                    lpparam.classLoader
                )

                // Android 17 / OEM ROMs may expose more than one overload. Do not assume
                // every overload has a Notification at args[6]. Resolve it by runtime type.
                val overloads = notificationManagerClass.declaredMethods
                    .filter { it.name == "enqueueNotificationInternal" }
                logToFile(
                    "找到 enqueueNotificationInternal 重载数: ${overloads.size}; " +
                        overloads.joinToString(" | ") { method ->
                            method.parameterTypes.joinToString(",") { it.simpleName } +
                                " -> " + method.returnType.simpleName
                        }
                )

                if (overloads.isEmpty()) {
                    logToFile("Hook失败: 未找到 enqueueNotificationInternal")
                } else {
                    XposedBridge.hookAllMethods(
                        notificationManagerClass,
                        "enqueueNotificationInternal",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val args = param.args ?: return
                                    val pkg = args.firstOrNull {
                                        it == "com.tencent.mobileqq" || it == "com.tencent.tim"
                                    } as? String ?: return
                                    val notificationIndex = args.indexOfFirst { it is Notification }
                                    if (notificationIndex < 0) return
                                    val notification = args[notificationIndex] as? Notification ?: return
                                    val extras = notification.extras ?: return

                                    // Notification ID is normally the parameter immediately before
                                    // Notification. Only rewrite it when that slot is really an Int.
                                    val idIndex = notificationIndex - 1
                                    if (idIndex >= 0 && args[idIndex] is Int) {
                                        param.args[idIndex] = System.currentTimeMillis().toInt()
                                    }

                                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                                        ?.toString()
                                        ?: ""
                                    val candidates = collectNotificationTexts(extras)
                                    val textField = selectNotificationText(candidates, title)
                                    val text = textField?.text.orEmpty()
                                    val allText = candidates.joinToString("\n") { it.text }

                                    if (INTEREST_PATTERN.containsMatchIn(title + "\n" + allText)) {
                                        logToFile(
                                            "QQ通知明细: 标题=$title 内容=${text.ifBlank { allText }} " +
                                                "通道=${notification.channelId}"
                                        )
                                    }

                                    handleQQNotification(
                                        param, extras, title, text, allText, textField?.key,
                                        blacklistModeQQ, groupsQQSet,
                                        removePrefix, muteGroupTodo, muteGroupAnnouncement
                                    )
                                } catch (t: Throwable) {
                                    // Fail open: a parsing/hook problem must not break notification delivery.
                                    logToFile(
                                        "通知 Hook 回调异常: ${t.javaClass.simpleName}: ${t.message}"
                                    )
                                }
                            }
                        }
                    )
                    logToFile("通知 Hook 已安装")
                }
            } catch (e: Throwable) {
                logToFile("Hook失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        } else if (lpparam.packageName == "io.github.xjyzs.systemednotificationblocker") {
            XposedHelpers.findAndHookMethod(
                "io.github.xjyzs.systemednotificationblocker.MainActivityKt",
                lpparam.classLoader,
                "isModuleActive",
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any {
                        return true
                    }
                }
            )
        }
    }

    private fun handleQQNotification(
        param: XC_MethodHook.MethodHookParam,
        extras: Bundle,
        title: String,
        text: String,
        allText: String,
        textKey: String?,
        blacklistMode: Boolean,
        groups: Set<String>,
        removePrefix: Boolean,
        muteGroupTodo: Boolean,
        muteGroupAnnouncement: Boolean
    ) {
        if (muteGroupAnnouncement && isQQGroupAnnouncement(title, allText)) {
            singleLog("QQ", title, text.ifBlank { allText })
            blockNotification(param)
            return
        }

        if (muteGroupTodo && isQQGroupTodo(title, allText)) {
            singleLog("QQ", title, text.ifBlank { allText })
            blockNotification(param)
            return
        }

        // Accept the old QQ prefix, the current [@全体成员] form, and common bracket variants.
        if (!isQQAllMessage(title, allText)) return

        var shouldBlock = !blacklistMode
        for (group in groups) {
            if (blacklistMode && group in title) {
                shouldBlock = true
                break
            } else if (!blacklistMode && group in title) {
                shouldBlock = false
                break
            }
        }

        if (shouldBlock) {
            singleLog("QQ", title, text.ifBlank { allText })
            blockNotification(param)
        } else if (removePrefix && textKey != null) {
            var newText = QQ_ALL_PREFIX.replaceFirst(text, "")
            // Keep the message body while removing the visible @all label if present.
            newText = newText.replaceFirst(Regex("([:：])\\s*@全体成员[ \\u00A0　]*"), "$1 ")
            if (newText != text) {
                if (textKey == Notification.EXTRA_TEXT_LINES) {
                    extras.putCharSequenceArray(textKey, arrayOf<CharSequence>(newText))
                } else {
                    extras.putCharSequence(textKey, newText)
                }
            }
        }
    }

    private data class NotificationText(val key: String, val text: String)

    private fun collectNotificationTexts(extras: Bundle): List<NotificationText> {
        val candidates = mutableListOf<NotificationText>()
        val keys = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_SUMMARY_TEXT
        )
        for (key in keys) {
            val value = extras.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }
            if (value != null) candidates.add(NotificationText(key, value))
        }

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            candidates.add(
                NotificationText(
                    Notification.EXTRA_TEXT_LINES,
                    lines.joinToString("\n") { it.toString() }
                )
            )
        }
        return candidates
    }

    private fun selectNotificationText(
        candidates: List<NotificationText>,
        title: String
    ): NotificationText? {
        return candidates.firstOrNull { isQQAllMessage(title, it.text) }
            ?: candidates.firstOrNull { it.key == Notification.EXTRA_TEXT }
            ?: candidates.maxByOrNull { it.text.length }
    }

    private fun isQQAllMessage(title: String, text: String): Boolean {
        return QQ_ALL_PATTERN.containsMatchIn(title + "\n" + text)
    }

    private fun isQQGroupTodo(title: String, text: String): Boolean {
        return "群待办" in title ||
            "群待办" in text ||
            ("还有待办需要处理" in title && "马上处理" in text)
    }

    private fun isQQGroupAnnouncement(title: String, text: String): Boolean {
        return "群公告" in title || "群公告" in text
    }

    // Android 17 adds a boolean-returning overload. Returning null there would make
    // the framework unbox null, so match the declared return type of each overload.
    private fun blockNotification(param: XC_MethodHook.MethodHookParam) {
        val returnType = (param.method as? java.lang.reflect.Method)?.returnType
        when (returnType) {
            java.lang.Boolean.TYPE -> param.result = false
            java.lang.Integer.TYPE -> param.result = 0
            java.lang.Long.TYPE -> param.result = 0L
            else -> param.result = null
        }
    }

    private companion object {
        val QQ_ALL_PREFIX = Regex(
            "^\\s*[\\[［【（(]\\s*(?:有全体消息|[@＠]\\s*全体(?:成员)?)\\s*[\\]］】）)]\\s*"
        )
        val QQ_ALL_PATTERN = Regex(
            "[@＠][\\s\\u200B-\\u200D\\uFEFF]*全体(成员)?|有全体消息|全体消息"
        )
        val INTEREST_PATTERN = Regex("全体|公告|待办|@")
    }
}

fun logToFile(text: String?) {
    try {
        val file = File("/data/system/SystemedNotificationBlockerLogs.txt")
        file.appendText(text + "\n")
    } catch (_: Exception) {
    }
}

fun singleLog(type: String, title: String, text: String) {
    logToFile(
        "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} " +
            "成功拦截 $type 消息："
    )
    logToFile("标题: $title")
    logToFile("内容: $text\n")
}
