package com.xxy.autoclickinstall.core

import android.content.Context
import androidx.core.content.edit

/**
 * 全局配置。所有进程内（App / 无障碍服务）共享同一份 SharedPreferences。
 */
object SettingsStore {

    private const val FILE = "autoclick_settings"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHOW_TOAST = "show_toast"
    private const val KEY_VIBRATE = "vibrate"
    private const val KEY_DELAY_MS = "delay_ms"
    private const val KEY_THROTTLE_MS = "throttle_ms"
    private const val KEY_MATCH_BUTTON_ID = "match_button_id"
    private const val KEY_AUTO_RESTORE = "auto_restore"
    private const val KEY_GESTURE_FALLBACK = "gesture_fallback"
    private const val KEY_DIAGNOSE = "diagnose"
    private const val KEY_PACKAGES = "packages"
    private const val KEY_CONFIRM_WORDS = "confirm_words"
    private const val KEY_BLOCK_WORDS = "block_words"
    private const val KEY_CLICK_COUNT = "click_count"

    /** 内置词库版本。升级内置默认词时递增，用于把新的默认配置推给老用户 */
    private const val KEY_SCHEMA = "schema"
    private const val SCHEMA_VERSION = 3

    /** 用户是否手动改过词表（改过则升级时只补新词，不覆盖其自定义） */
    private const val KEY_CUSTOMIZED = "customized"

    /** 小米澎湃 OS 的 USB 安装确认弹窗来自安全中心 */
    val DEFAULT_PACKAGES: Set<String> = linkedSetOf(
        "com.miui.securitycenter"
    )

    /** 只点这一个按钮 */
    val DEFAULT_CONFIRM_WORDS: Set<String> = linkedSetOf(
        "继续安装"
    )

    /** 安全网：文本里同时出现「拒绝 / 取消」的按钮一律不点（不影响「继续安装」） */
    val DEFAULT_BLOCK_WORDS: Set<String> = linkedSetOf(
        "拒绝",
        "取消"
    )

    private fun prefs() = AppContext.get().getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 用户是否手动改过词表 */
    var customized: Boolean
        get() = prefs().getBoolean(KEY_CUSTOMIZED, false)
        set(value) = prefs().edit { putBoolean(KEY_CUSTOMIZED, value) }

    /**
     * 内置配置升级：
     * - 未手动改过 → 直接替换为新的内置默认值（能删掉废弃的旧词）
     * - 手动改过   → 只补新的默认项，保留用户自定义
     */
    fun migrate() {
        val prefs = prefs()
        if (prefs.getInt(KEY_SCHEMA, 0) >= SCHEMA_VERSION) return

        val keepCustom = customized

        val packages = if (keepCustom) targetPackages + DEFAULT_PACKAGES else DEFAULT_PACKAGES
        val confirms = if (keepCustom) confirmWords + DEFAULT_CONFIRM_WORDS else DEFAULT_CONFIRM_WORDS
        val blocks = if (keepCustom) blockWords + DEFAULT_BLOCK_WORDS else DEFAULT_BLOCK_WORDS

        prefs.edit {
            putInt(KEY_SCHEMA, SCHEMA_VERSION)
            putString(KEY_PACKAGES, packages.joinToString("\n"))
            putString(KEY_CONFIRM_WORDS, confirms.joinToString("\n"))
            putString(KEY_BLOCK_WORDS, blocks.joinToString("\n"))
        }
    }

    var enabled: Boolean
        get() = prefs().getBoolean(KEY_ENABLED, true)
        set(value) = prefs().edit { putBoolean(KEY_ENABLED, value) }

    /** 默认关闭：静默处理，不在别人 App 上方弹提示 */
    var showToast: Boolean
        get() = prefs().getBoolean(KEY_SHOW_TOAST, false)
        set(value) = prefs().edit { putBoolean(KEY_SHOW_TOAST, value) }

    var vibrate: Boolean
        get() = prefs().getBoolean(KEY_VIBRATE, false)
        set(value) = prefs().edit { putBoolean(KEY_VIBRATE, value) }

    /** 弹窗出现后延迟多久再点击（毫秒） */
    var delayMs: Int
        get() = prefs().getInt(KEY_DELAY_MS, 300)
        set(value) = prefs().edit { putInt(KEY_DELAY_MS, value) }

    /** 同一按钮的最小重复点击间隔（毫秒），防止疯狂点击 */
    var throttleMs: Int
        get() = prefs().getInt(KEY_THROTTLE_MS, 1200)
        set(value) = prefs().edit { putInt(KEY_THROTTLE_MS, value) }

    /** 文本匹配不到时，是否尝试点击对话框的正向按钮（android:id/button1）。默认关闭，避免误点其它按钮 */
    var matchPositiveButton: Boolean
        get() = prefs().getBoolean(KEY_MATCH_BUTTON_ID, false)
        set(value) = prefs().edit { putBoolean(KEY_MATCH_BUTTON_ID, value) }

    /** 服务被系统回收 / 被关闭后，下次打开应用时自动重新写入并开启 */
    var autoRestore: Boolean
        get() = prefs().getBoolean(KEY_AUTO_RESTORE, true)
        set(value) = prefs().edit { putBoolean(KEY_AUTO_RESTORE, value) }

    /**
     * 兜底：performAction(ACTION_CLICK) 在高权限系统弹窗上可能直接失败，
     * 此时改用无障碍手势按屏幕坐标点击（dispatchGesture 走系统输入通道）。
     */
    var gestureFallback: Boolean
        get() = prefs().getBoolean(KEY_GESTURE_FALLBACK, true)
        set(value) = prefs().edit { putBoolean(KEY_GESTURE_FALLBACK, value) }

    /** 诊断模式：把所有窗口及其命中的按钮文本写入日志，用于排查抓不到的弹窗 */
    var diagnoseEnabled: Boolean
        get() = prefs().getBoolean(KEY_DIAGNOSE, false)
        set(value) = prefs().edit { putBoolean(KEY_DIAGNOSE, value) }

    var targetPackages: Set<String>
        get() = readSet(KEY_PACKAGES, DEFAULT_PACKAGES)
        set(value) = writeSet(KEY_PACKAGES, value)

    var confirmWords: Set<String>
        get() = readSet(KEY_CONFIRM_WORDS, DEFAULT_CONFIRM_WORDS)
        set(value) = writeSet(KEY_CONFIRM_WORDS, value)

    var blockWords: Set<String>
        get() = readSet(KEY_BLOCK_WORDS, DEFAULT_BLOCK_WORDS)
        set(value) = writeSet(KEY_BLOCK_WORDS, value)

    var clickCount: Long
        get() = prefs().getLong(KEY_CLICK_COUNT, 0L)
        set(value) = prefs().edit { putLong(KEY_CLICK_COUNT, value) }

    /** 先判黑名单词，再判确认词，避免「取消安装」被误判为「安装」 */
    fun isConfirmText(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (blockWords.any { t.contains(it, ignoreCase = true) }) return false
        return confirmWords.any { t.contains(it, ignoreCase = true) }
    }

    private fun readSet(key: String, fallback: Set<String>): Set<String> {
        // 用 contains 区分「从未设置」和「用户主动清空」，避免清空后又被默认值覆盖
        if (!prefs().contains(key)) return fallback
        val raw = prefs().getString(key, "") ?: ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun writeSet(key: String, value: Set<String>) {
        prefs().edit { putString(key, value.joinToString("\n")) }
    }
}
