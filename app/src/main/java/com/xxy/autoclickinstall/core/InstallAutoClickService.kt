package com.xxy.autoclickinstall.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * 监听系统安装器弹窗并自动点击确认按钮。
 *
 * USB 安装确认等系统弹窗通常不是「当前活动窗口」，[rootInActiveWindow] 取不到，
 * 因此需要遍历 [windows] 里的所有窗口；若 performAction(ACTION_CLICK) 仍失败
 * （高权限窗口常见），再用无障碍手势按屏幕坐标点击。
 */
class InstallAutoClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val lastClickAt = HashMap<String, Long>()

    private val retryIntervalMs = 600L
    private val maxRetries = 16  // ≈9.6s，覆盖小米 USB 安装确认的 9s 倒计时
    private var retryCount = 0

    private val retryRunnable: Runnable = Runnable {
        val clicked = scanAndClickOnce()
        if (clicked) {
            stopRetry()
        } else if (++retryCount >= maxRetries) {
            stopRetry()
        } else {
            handler.postDelayed(retryRunnable, retryIntervalMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = buildServiceInfo()
        AppState.setServiceRunning(true)
        AppState.log("无障碍服务已启动")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppState.setServiceRunning(false)
        AppState.log("无障碍服务已断开")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopRetry()
        AppState.setServiceRunning(false)
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopRetry()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!SettingsStore.enabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Unit

            else -> return
        }

        val pkg = event.packageName?.toString() ?: return

        // 诊断模式下不过滤包名，把所有窗口都 dump 出来
        if (SettingsStore.diagnoseEnabled) dumpWindows("事件 pkg=$pkg")

        if (pkg !in SettingsStore.targetPackages) return

        // 收到任何一个相关窗口事件就（重）启动持续扫描：
        // - 倒计时每秒触发 WINDOW_CONTENT_CHANGED → 重置计数，retry 不会超时
        // - 窗口消失后不再收到该包事件 → retry 跑完 maxRetries 后自动停
        startRetry()
    }

    private fun startRetry() {
        stopRetry()
        retryCount = 0
        handler.postDelayed(retryRunnable, 80L)
    }

    private fun stopRetry() {
        handler.removeCallbacks(retryRunnable)
        retryCount = 0
    }

    private fun scanAndClickOnce(): Boolean {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val windows = ArrayList<AccessibilityWindowInfo>()

        runCatching { rootInActiveWindow }.getOrNull()?.let { roots.add(it) }

        // 关键：高权限系统弹窗往往不在 active 窗口里，必须遍历全部窗口
        for (window in runCatching { windows }.getOrNull().orEmpty()) {
            runCatching { window.root }.getOrNull()?.let { roots.add(it) }
            windows.add(window)
        }

        return try {
            for (root in roots) {
                if (clickTargetIn(root)) return true
            }
            false
        } finally {
            roots.forEach { runCatching { it.recycle() } }
            windows.forEach { runCatching { it.recycle() } }
        }
    }

    private fun clickTargetIn(root: AccessibilityNodeInfo): Boolean {
        // 遍历全部窗口时必须重新校验包名，否则会去点桌面等无关窗口里的文本
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        if (!isPackageAllowed(pkg)) return false

        val target = findTarget(root) ?: return false

        val now = SystemClock.elapsedRealtime()
        val previous = lastClickAt[target.text] ?: 0L
        if (now - previous < SettingsStore.throttleMs) return false

        if (!performClickNode(target.node)) return false

        lastClickAt[target.text] = now
        SettingsStore.clickCount += 1
        AppState.log("已自动点击「${target.text}」（${pkg ?: "?"}）")
        if (SettingsStore.showToast) {
            Toast.makeText(this, "自动确认：${target.text}", Toast.LENGTH_SHORT).show()
        }
        if (SettingsStore.vibrate) vibrate()
        return true
    }

    /**
     * 按优先级选目标，避免点到「USB安装提示」这种标题文本：
     *   1) 文本命中确认词 且 自身可点击  → 一定是按钮，直接采用
     *   2) 文本命中确认词 但自身不可点击 → 仅当它位于按钮容器内才采用（兜底）
     *   3) 对话框正向按钮（android:id/button1 等）
     */
    private fun findTarget(root: AccessibilityNodeInfo): Target? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var loose: Target? = null
        var positiveButton: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = node.text?.toString().orEmpty()
                .ifBlank { node.contentDescription?.toString().orEmpty() }
                .trim()

            if (text.isNotEmpty() && SettingsStore.isConfirmText(text)) {
                if (node.isClickable) {
                    return Target(node, text)
                }
                // 不可点击的文本多为标题/说明，只有落在按钮容器里才当作按钮
                if (loose == null && hasClickableAncestor(node, 3)) {
                    loose = Target(node, text)
                }
            }

            if (SettingsStore.matchPositiveButton && positiveButton == null && isPositiveButton(node)) {
                positiveButton = node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }

        return loose ?: positiveButton?.let { Target(it, "正向按钮") }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo, maxDepth: Int): Boolean {
        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth++ < maxDepth) {
            if (parent.isClickable) return true
            parent = runCatching { parent.parent }.getOrNull()
        }
        return false
    }

    /** 取不到包名的系统窗口放行，取到就必须命中白名单 */
    private fun isPackageAllowed(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        return pkg in SettingsStore.targetPackages
    }

    private fun isPositiveButton(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        val id = node.viewIdResourceName ?: return false
        return id.endsWith("id/button1") ||
            id.endsWith("id/ok_button") ||
            id.endsWith("id/positive_button") ||
            id.endsWith("id/confirm_button") ||
            id.endsWith("id/btn_confirm")
    }

    /** 逐级降级：直接点击 → 可点击父容器 → 手势坐标点击 */
    private fun performClickNode(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var parent = runCatching { node.parent }.getOrNull()
        var depth = 0
        while (parent != null && depth++ < 5) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = runCatching { parent.parent }.getOrNull()
        }

        return clickByGesture(node)
    }

    /**
     * 兜底：按节点屏幕坐标派发一次点击手势。
     * 走系统输入通道，对拒绝 performAction 的高权限系统弹窗有效。
     */
    private fun clickByGesture(node: AccessibilityNodeInfo): Boolean {
        if (!SettingsStore.gestureFallback) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        return runCatching {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) return false

            val path = Path().apply {
                moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val effect = VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
            } else {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(effect)
            }
        }
    }

    // ---------- 诊断 ----------

    private var lastDumpAt = 0L

    private fun dumpWindows(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDumpAt < 1500L) return  // 节流，避免日志爆炸
        lastDumpAt = now

        val all = runCatching { windows }.getOrNull().orEmpty()
        val report = StringBuilder("$reason → ${all.size} 个窗口")

        all.take(6).forEachIndexed { index, window ->
            val root = runCatching { window.root }.getOrNull()
            val pkg = root?.let { runCatching { it.packageName?.toString() }.getOrNull() } ?: "?"

            report.append("\n#$index ${windowTypeName(window.type)} pkg=$pkg")
            if (runCatching { window.isActive }.getOrDefault(false)) report.append(" [active]")
            if (!isPackageAllowed(pkg)) report.append(" [跳过]")

            // 列出该窗口里所有可点击控件及其文本，用于确认按钮的真实文案
            if (root != null) {
                val buttons = collectClickableTexts(root, 6)
                if (buttons.isNotEmpty()) {
                    report.append("\n    按钮: ${buttons.joinToString(" / ")}")
                }
                runCatching { root.recycle() }
            }
        }

        all.forEach { runCatching { it.recycle() } }
        AppState.log(report.toString())
    }

    /** 收集窗口内所有可点击控件的文本（BFS，限制遍历规模） */
    private fun collectClickableTexts(root: AccessibilityNodeInfo, limit: Int): List<String> {
        val result = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 300 && result.size < limit) {
            val node = queue.removeFirst()
            visited++

            if (node.isClickable) {
                val text = node.text?.toString().orEmpty()
                    .ifBlank { node.contentDescription?.toString().orEmpty() }
                    .trim()
                if (text.isNotEmpty()) result.add(text)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it) }
            }
        }
        return result.toList()
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVIDER"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y"
        else -> "TYPE_$type"
    }

    private fun buildServiceInfo() = AccessibilityServiceInfo().apply {
        eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        notificationTimeout = 100
        flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
    }

    private class Target(val node: AccessibilityNodeInfo, val text: String)
}
