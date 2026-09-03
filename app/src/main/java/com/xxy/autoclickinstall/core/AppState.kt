package com.xxy.autoclickinstall.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val id: Long,
    val time: String,
    val message: String
)

/**
 * 运行期状态：无障碍服务是否活着 + 最近的自动点击日志。
 * 无障碍服务与界面处于同一进程，因此可以直接共享。
 */
object AppState {

    var serviceRunning by mutableStateOf(false)
        private set

    internal fun setServiceRunning(running: Boolean) {
        serviceRunning = running
    }

    private const val MAX = 60

    private var seq = 0L

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(message: String) {
        val entry = LogEntry(
            id = ++seq,
            time = formatter.format(Date()),
            message = message
        )
        _logs.value = (listOf(entry) + _logs.value).take(MAX)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
