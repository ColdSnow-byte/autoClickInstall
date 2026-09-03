package com.xxy.autoclickinstall.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import com.xxy.autoclickinstall.core.AppState

enum class ShizukuStatus {
    /** Shizuku 应用未安装 */
    NOT_INSTALLED,

    /** Shizuku 已安装但服务未启动 / Binder 未就绪 */
    WAITING_BINDER,

    /** Binder 就绪，但尚未授权本应用 */
    PERMISSION_REQUIRED,

    /** 已授权，可以执行 shell 命令 */
    AUTHORIZED
}

data class ExecResult(val code: Int, val out: String, val err: String) {
    val isSuccess: Boolean get() = code == 0
    val message: String
        get() = listOf(out, err)
            .filter { it.isNotBlank() }
            .joinToString(" | ")
            .ifBlank { "exit=$code" }
}

object ShizukuHelper {

    const val REQUEST_CODE = 20240

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val _status = MutableStateFlow(ShizukuStatus.WAITING_BINDER)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    /** Shizuku 服务端版本号，-1 表示拿不到（Binder 未就绪） */
    private val _version = MutableStateFlow(-1)
    val version: StateFlow<Int> = _version.asStateFlow()

    private var appContext: Context? = null
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext

        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                AppState.log("已收到 Shizuku Binder（v${currentVersion()}）")
                refresh()
            }
        })

        Shizuku.addBinderDeadListener {
            AppState.log("Shizuku Binder 已断开")
            _status.value = ShizukuStatus.WAITING_BINDER
        }

        Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                AppState.log(if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝（$grantResult）")
                refresh()
            }
        })

        refresh()
    }

    fun refresh() {
        val alive = pingBinder()
        _version.value = currentVersion()

        _status.value = when {
            !alive -> if (isShizukuInstalled()) ShizukuStatus.WAITING_BINDER else ShizukuStatus.NOT_INSTALLED
            isGranted() -> ShizukuStatus.AUTHORIZED
            else -> ShizukuStatus.PERMISSION_REQUIRED
        }
    }

    private fun pingBinder(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun currentVersion(): Int = try {
        Shizuku.getVersion()
    } catch (t: Throwable) {
        -1
    }

    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 返回 null 表示请求已发出；返回非空则为失败原因，需要展示给用户 */
    fun requestPermission(): String? {
        if (!pingBinder()) {
            return "Shizuku Binder 尚未就绪：请确认 Shizuku 服务已启动，然后「彻底关闭本应用再重新打开」（必要时重启 Shizuku 服务）"
        }

        return try {
            Shizuku.requestPermission(REQUEST_CODE)
            null
        } catch (t: Throwable) {
            AppState.log("申请授权异常：${t.message}")
            "申请授权失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    /** 在 Shizuku（shell / root）身份下执行命令 */
    fun exec(vararg command: String): ExecResult {
        if (!isGranted()) return ExecResult(-1, "", "Shizuku 未授权")

        return try {
            val binder = Shizuku.getBinder() ?: return ExecResult(-1, "", "Shizuku Binder 不可用")
            val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
            val process: IRemoteProcess = service.newProcess(arrayOf(*command), null, null)

            // 关闭标准输入，避免命令等待输入而挂起
            runCatching {
                process.outputStream?.let {
                    ParcelFileDescriptor.AutoCloseOutputStream(it).close()
                }
            }

            val out = StringBuilder()
            val err = StringBuilder()
            val readers = listOf(
                Thread { drain(process.inputStream, out) },
                Thread { drain(process.errorStream, err) }
            )
            readers.forEach { it.start() }

            val code = try {
                process.waitFor()
            } finally {
                readers.forEach { it.join(3000) }
                runCatching { process.destroy() }
            }

            ExecResult(code, out.toString(), err.toString())
        } catch (t: Throwable) {
            ExecResult(-1, "", t.message ?: "执行命令失败")
        }
    }

    private fun drain(pfd: ParcelFileDescriptor?, sink: StringBuilder) {
        if (pfd == null) return
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
                sink.append(stream.bufferedReader().readText())
            }
        }
    }

    fun launchShizuku(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    @Suppress("DEPRECATION")
    private fun isShizukuInstalled(): Boolean {
        val ctx = appContext ?: return true
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }
}
