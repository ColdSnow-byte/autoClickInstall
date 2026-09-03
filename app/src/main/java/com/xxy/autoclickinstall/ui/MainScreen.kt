package com.xxy.autoclickinstall.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xxy.autoclickinstall.core.AppState
import com.xxy.autoclickinstall.core.LogEntry
import com.xxy.autoclickinstall.core.SettingsStore
import com.xxy.autoclickinstall.shizuku.Activator
import com.xxy.autoclickinstall.shizuku.ShizukuHelper
import com.xxy.autoclickinstall.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val shizukuStatus by ShizukuHelper.status.collectAsStateWithLifecycle()
    val shizukuVersion by ShizukuHelper.version.collectAsStateWithLifecycle()
    val logs by AppState.logs.collectAsStateWithLifecycle()
    val serviceRunning = AppState.serviceRunning

    var enabled by remember { mutableStateOf(SettingsStore.enabled) }
    var showToast by remember { mutableStateOf(SettingsStore.showToast) }
    var vibrate by remember { mutableStateOf(SettingsStore.vibrate) }
    var matchPositive by remember { mutableStateOf(SettingsStore.matchPositiveButton) }
    var delayMs by remember { mutableStateOf(SettingsStore.delayMs.toFloat()) }
    var packages by remember { mutableStateOf(SettingsStore.targetPackages) }
    var confirmWords by remember { mutableStateOf(SettingsStore.confirmWords) }
    var blockWords by remember { mutableStateOf(SettingsStore.blockWords) }
    var autoRestore by remember { mutableStateOf(SettingsStore.autoRestore) }
    var gestureFallback by remember { mutableStateOf(SettingsStore.gestureFallback) }
    var diagnose by remember { mutableStateOf(SettingsStore.diagnoseEnabled) }
    var activating by remember { mutableStateOf(false) }
    // 用户点了「申请授权」后，授权一通过就自动接着完成剩下的激活步骤
    var autoActivateAfterGrant by remember { mutableStateOf(false) }

    suspend fun runActivate() {
        activating = true
        val result = withContext(Dispatchers.IO) { Activator.activate(context) }
        activating = false
        ShizukuHelper.refresh()
        snackbar.showSnackbar(result)
    }

    // 打开界面时自动修复被系统回收掉的无障碍服务
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { Activator.restoreIfNeeded(context) }
    }

    // Shizuku 授权通过后，无需再点一次「一键激活」
    LaunchedEffect(shizukuStatus) {
        if (!autoActivateAfterGrant || shizukuStatus != ShizukuStatus.AUTHORIZED) return@LaunchedEffect
        autoActivateAfterGrant = false
        runActivate()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("自动安装确认") },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { openAccessibilitySettings(context) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "系统无障碍设置")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                StatusCard(shizukuStatus, shizukuVersion, serviceRunning)
            }

            item {
                ActivateCard(
                    status = shizukuStatus,
                    activating = activating,
                    onPrimary = {
                        scope.launch {
                            if (shizukuStatus == ShizukuStatus.AUTHORIZED) {
                                runActivate()
                            } else {
                                val error = ShizukuHelper.requestPermission()
                                if (error != null) {
                                    AppState.log(error)
                                    snackbar.showSnackbar(error)
                                } else {
                                    autoActivateAfterGrant = true
                                    snackbar.showSnackbar("已发起授权，通过后会自动完成剩余步骤")
                                }
                            }
                        }
                    },
                    onOpenShizuku = {
                        if (!ShizukuHelper.launchShizuku(context)) {
                            Toast.makeText(context, "未检测到 Shizuku 应用", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenAccessibility = { openAccessibilitySettings(context) }
                )
            }

            item {
                AutomationCard(
                    enabled = enabled,
                    showToast = showToast,
                    vibrate = vibrate,
                    matchPositive = matchPositive,
                    autoRestore = autoRestore,
                    gestureFallback = gestureFallback,
                    diagnose = diagnose,
                    delayMs = delayMs,
                    onEnabledChange = { enabled = it; SettingsStore.enabled = it },
                    onShowToastChange = { showToast = it; SettingsStore.showToast = it },
                    onVibrateChange = { vibrate = it; SettingsStore.vibrate = it },
                    onMatchPositiveChange = { matchPositive = it; SettingsStore.matchPositiveButton = it },
                    onAutoRestoreChange = { autoRestore = it; SettingsStore.autoRestore = it },
                    onGestureFallbackChange = { gestureFallback = it; SettingsStore.gestureFallback = it },
                    onDiagnoseChange = { diagnose = it; SettingsStore.diagnoseEnabled = it },
                    onDelayChange = {
                        delayMs = it
                        SettingsStore.delayMs = it.toInt()
                    }
                )
            }

            item {
                WordListCard(
                    title = "确认关键词",
                    supporting = "弹窗中出现这些文字的按钮会被自动点击",
                    icon = Icons.Outlined.Done,
                    items = confirmWords,
                    onAdd = {
                        confirmWords = confirmWords + it
                        SettingsStore.confirmWords = confirmWords
                    },
                    onRemove = {
                        confirmWords = confirmWords - it
                        SettingsStore.confirmWords = confirmWords
                    },
                    onReset = {
                        confirmWords = SettingsStore.DEFAULT_CONFIRM_WORDS
                        SettingsStore.confirmWords = confirmWords
                    }
                )
            }

            item {
                WordListCard(
                    title = "排除关键词",
                    supporting = "包含这些文字的按钮永不点击，优先级高于确认关键词",
                    icon = Icons.Outlined.Warning,
                    items = blockWords,
                    onAdd = {
                        blockWords = blockWords + it
                        SettingsStore.blockWords = blockWords
                    },
                    onRemove = {
                        blockWords = blockWords - it
                        SettingsStore.blockWords = blockWords
                    },
                    onReset = {
                        blockWords = SettingsStore.DEFAULT_BLOCK_WORDS
                        SettingsStore.blockWords = blockWords
                    }
                )
            }

            item {
                WordListCard(
                    title = "生效的应用",
                    supporting = "仅在这些应用的界面中自动点击（填写包名）",
                    icon = Icons.Outlined.TouchApp,
                    items = packages,
                    onAdd = {
                        packages = packages + it
                        SettingsStore.targetPackages = packages
                    },
                    onRemove = {
                        packages = packages - it
                        SettingsStore.targetPackages = packages
                    },
                    onReset = {
                        packages = SettingsStore.DEFAULT_PACKAGES
                        SettingsStore.targetPackages = packages
                    }
                )
            }

            item {
                LogCard(logs = logs, onClear = { AppState.clearLogs() })
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .let(context::startActivity)
    }
}

@Composable
private fun StatusCard(status: ShizukuStatus, shizukuVersion: Int, serviceRunning: Boolean) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("运行状态", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            val (shizukuText, shizukuOk) = when (status) {
                ShizukuStatus.AUTHORIZED -> "已授权" to true
                ShizukuStatus.PERMISSION_REQUIRED -> "等待授权" to false
                ShizukuStatus.WAITING_BINDER -> "服务未启动" to false
                ShizukuStatus.NOT_INSTALLED -> "未安装" to false
            }

            StatusRow(
                label = "Shizuku",
                value = if (shizukuVersion >= 11) "$shizukuText · v$shizukuVersion" else shizukuText,
                ok = shizukuOk
            )

            StatusRow(
                label = "无障碍服务",
                value = if (serviceRunning) "运行中" else "未运行",
                ok = serviceRunning
            )

            StatusRow(
                label = "已自动确认",
                value = "${SettingsStore.clickCount} 次",
                ok = serviceRunning
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = color
                ) {
                    Text(
                        text = value,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = if (ok) Icons.Outlined.Done else Icons.Outlined.Close,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivateCard(
    status: ShizukuStatus,
    activating: Boolean,
    onPrimary: () -> Unit,
    onOpenShizuku: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("一键激活", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "只需点一次：Shizuku 授权通过后会自动授予 WRITE_SECURE_SETTINGS、开启自动点击所需的无障碍服务，并把本应用加入后台白名单，全程无需手动去系统设置里操作。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            val primaryText = when (status) {
                ShizukuStatus.NOT_INSTALLED -> "请先安装 Shizuku"
                ShizukuStatus.WAITING_BINDER -> "连接 Shizuku 并激活"
                ShizukuStatus.PERMISSION_REQUIRED -> "授权并激活"
                ShizukuStatus.AUTHORIZED -> "一键激活"
            }

            Button(
                onClick = onPrimary,
                enabled = status != ShizukuStatus.NOT_INSTALLED && !activating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Bolt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (activating) "正在激活…" else primaryText)
            }

            Spacer(Modifier.height(8.dp))

            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onOpenShizuku,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Launch, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("打开 Shizuku")
                }

                Spacer(Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.weight(1f)
                ) { Text("手动开启") }
            }

            if (status != ShizukuStatus.AUTHORIZED) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Text(
                        text = when (status) {
                            ShizukuStatus.NOT_INSTALLED -> "未检测到 Shizuku。请先安装 Shizuku 并按其指引启动（推荐「无线调试」方式）。"
                            ShizukuStatus.WAITING_BINDER -> "Shizuku 服务已安装但 Binder 未送达本应用。请确认 Shizuku 服务已启动，然后从最近任务中「彻底关闭本应用再重新打开」；若仍不行，在 Shizuku 里停止并重新启动服务。"
                            else -> "Shizuku 已就绪。点击上方按钮，在弹出的授权窗口中允许，之后会自动完成无障碍服务的开启。"
                        },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomationCard(
    enabled: Boolean,
    showToast: Boolean,
    vibrate: Boolean,
    matchPositive: Boolean,
    autoRestore: Boolean,
    gestureFallback: Boolean,
    diagnose: Boolean,
    delayMs: Float,
    onEnabledChange: (Boolean) -> Unit,
    onShowToastChange: (Boolean) -> Unit,
    onVibrateChange: (Boolean) -> Unit,
    onMatchPositiveChange: (Boolean) -> Unit,
    onAutoRestoreChange: (Boolean) -> Unit,
    onGestureFallbackChange: (Boolean) -> Unit,
    onDiagnoseChange: (Boolean) -> Unit,
    onDelayChange: (Float) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("自动化设置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            SwitchRow("自动点击安装确认", "总开关，关闭后不再处理任何弹窗", enabled, onEnabledChange)
            HorizontalDivider()
            SwitchRow("点击后显示提示", "默认关闭，全程静默；开启后会在屏幕底部提示被点击的按钮", showToast, onShowToastChange)
            HorizontalDivider()
            SwitchRow("点击后震动反馈", null, vibrate, onVibrateChange)
            HorizontalDivider()
            SwitchRow(
                "兜底点击对话框正向按钮",
                "文本匹配不到时，尝试点击 android:id/button1",
                matchPositive,
                onMatchPositiveChange
            )
            HorizontalDivider()
            SwitchRow(
                "自动重新启用服务",
                "服务被系统回收后，下次打开应用时自动恢复，无需重新授权",
                autoRestore,
                onAutoRestoreChange
            )
            HorizontalDivider()
            SwitchRow(
                "手势坐标兜底",
                "常规点击对系统弹窗失效时，改用无障碍手势按坐标点击",
                gestureFallback,
                onGestureFallbackChange
            )
            HorizontalDivider()
            SwitchRow(
                "诊断模式",
                "把弹窗所属窗口、包名和命中的按钮写进日志，用于排查抓不到的弹窗",
                diagnose,
                onDiagnoseChange
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "弹窗出现后延迟 ${delayMs.toInt()} ms 再点击",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = delayMs,
                onValueChange = onDelayChange,
                valueRange = 0f..2000f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun WordListCard(
    title: String,
    supporting: String,
    icon: ImageVector,
    items: Set<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = {
                    SettingsStore.customized = false
                    onReset()
                }) { Text("重置") }
            }

            Spacer(Modifier.height(10.dp))

            if (items.isEmpty()) {
                Text(
                    "暂无条目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items.forEach { word ->
                        AssistChip(
                            onClick = {
                                SettingsStore.customized = true
                                onRemove(word)
                            },
                            label = { Text(word) },
                            trailingIcon = {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "删除 $word",
                                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(onClick = { showDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        }
    }

    if (showDialog) {
        AddItemDialog(
            title = "添加到「$title」",
            label = title,
            onDismiss = { showDialog = false },
            onConfirm = {
                if (it.isNotBlank()) {
                    SettingsStore.customized = true
                    onAdd(it.trim())
                }
                showDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = false },
                    label = { Text(label) },
                    singleLine = true,
                    isError = error,
                    supportingText = if (error) {
                        { Text("内容不能为空", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (text.isBlank()) error = true else onConfirm(text)
                    }) { Text("添加") }
                }
            }
        }
    }
}

@Composable
private fun LogCard(logs: List<LogEntry>, onClear: () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("运行日志", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空")
                }
            }

            Spacer(Modifier.height(8.dp))

            SelectionContainer {
                Column {
                    if (logs.isEmpty()) {
                        Text(
                            "暂无记录。激活成功之后，安装应用时的自动确认会记录在这里。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        logs.take(15).forEach { entry ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Text(
                                    entry.time,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    entry.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
