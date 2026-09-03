package com.xxy.autoclickinstall.shizuku

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.xxy.autoclickinstall.core.AppState
import com.xxy.autoclickinstall.core.AppContext
import com.xxy.autoclickinstall.core.InstallAutoClickService
import com.xxy.autoclickinstall.core.SettingsStore

object Activator {

    const val PERMISSION = Manifest.permission.WRITE_SECURE_SETTINGS

    val COMPONENT_NAME: String by lazy {
        "${AppContext.get().packageName}/${InstallAutoClickService::class.java.name}"
    }

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 借助 Shizuku 以 shell 身份授予 WRITE_SECURE_SETTINGS */
    fun grantWriteSecureSettings(): Boolean {
        val pkg = AppContext.get().packageName
        val result = ShizukuHelper.exec("pm", "grant", pkg, PERMISSION)
        AppState.log("pm grant WRITE_SECURE_SETTINGS → ${if (result.isSuccess) "成功" else result.message}")
        return result.isSuccess
    }

    /** 是否已经把本应用的无障碍服务写进了系统已启用列表 */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = readEnabledServices(context)
        return enabled.any { it.equals(COMPONENT_NAME, ignoreCase = true) }
    }

    fun setAccessibilityEnabled(context: Context, enable: Boolean): Boolean {
        val current = readEnabledServices(context).toMutableList()
        when {
            enable && !current.any { it.equals(COMPONENT_NAME, ignoreCase = true) } ->
                current.add(COMPONENT_NAME)

            !enable -> current.removeAll { it.equals(COMPONENT_NAME, ignoreCase = true) }
        }

        val value = current.joinToString(":")
        val on = if (current.isEmpty()) "0" else "1"

        val ok = if (hasWriteSecureSettings(context)) {
            val resolver = context.contentResolver
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
            Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, on)
            isAccessibilityEnabled(context)
        } else {
            // 没有 WRITE_SECURE_SETTINGS 时退回用 Shizuku 直接写 settings 表
            ShizukuHelper.exec("settings", "put", "secure", Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value).isSuccess &&
                ShizukuHelper.exec("settings", "put", "secure", Settings.Secure.ACCESSIBILITY_ENABLED, on).isSuccess
        }

        AppState.log(if (enable) "开启无障碍服务${if (ok) "成功" else "失败"}" else "关闭无障碍服务${if (ok) "成功" else "失败"}")
        return ok
    }

    /**
     * 用 Shizuku 把本应用加入后台白名单，降低被系统回收的概率。
     * 属于「尽力而为」的操作，失败不影响自动点击本身。
     */
    fun applyBackgroundWhitelist(): Boolean {
        val pkg = AppContext.get().packageName
        var ok = false

        val appops = ShizukuHelper.exec("cmd", "appops", "set", pkg, "RUN_IN_BACKGROUND", "allow")
        AppState.log("appops RUN_IN_BACKGROUND → ${if (appops.isSuccess) "已允许" else appops.message}")
        ok = ok or appops.isSuccess

        val idle = ShizukuHelper.exec("dumpsys", "deviceidle", "whitelist", "+$pkg")
        AppState.log("加入 Doze 白名单 → ${if (idle.isSuccess) "成功" else idle.message}")
        ok = ok or idle.isSuccess

        return ok
    }

    /**
     * 一次性完成所有授权：
     * 1. 通过 Shizuku 授予 WRITE_SECURE_SETTINGS
     * 2. 把本应用的无障碍服务写入系统设置并开启
     * 3. 顺带加入后台白名单，让应用在后台静默运行
     */
    fun activate(context: Context): String {
        if (ShizukuHelper.status.value != ShizukuStatus.AUTHORIZED) {
            return "请先在 Shizuku 中授权本应用"
        }

        if (!hasWriteSecureSettings(context) && !grantWriteSecureSettings()) {
            AppState.log("授予 WRITE_SECURE_SETTINGS 失败，改用 Shizuku 直接写设置")
        }

        // 个别 ROM 授予后需要一点时间生效，写设置失败时做一次重试
        if (!setAccessibilityEnabled(context, true)) {
            setAccessibilityEnabled(context, true)
        }

        applyBackgroundWhitelist()

        return if (isAccessibilityEnabled(context)) {
            "全部授权完成：无障碍服务已开启，安装弹窗将自动确认"
        } else {
            "开启失败，请尝试手动在系统「无障碍」中开启本服务"
        }
    }

    /**
     * 保活检查：如果服务已从系统设置中被移除（例如被系统回收或用户在别处关闭），
     * 且当前仍有能力写入安全设置，则自动重新开启。
     */
    fun restoreIfNeeded(context: Context): Boolean {
        if (!SettingsStore.autoRestore) return false
        if (isAccessibilityEnabled(context)) return false
        if (!hasWriteSecureSettings(context) &&
            ShizukuHelper.status.value != ShizukuStatus.AUTHORIZED
        ) return false

        return setAccessibilityEnabled(context, true).also {
            if (it) AppState.log("检测到服务已关闭，已自动重新启用")
        }
    }

    private fun readEnabledServices(context: Context): List<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(":").filter { it.isNotBlank() }
    }
}
