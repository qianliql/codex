package com.example.lightledger.notification

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Only our exit reason and lifecycle times; no descriptions, traces or payment content. */
internal object ProcessExitDiagnostics {
    private const val PREFS = "process_exit_diagnostics"

    fun capture(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val latest = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter { it.processName == context.applicationInfo.processName }
                .maxByOrNull { it.timestamp } ?: return
            val prefs = context.getSharedPreferences(PREFS, 0)
            if (latest.timestamp > prefs.getLong("exit_time", 0)) {
                prefs.edit().putLong("exit_time", latest.timestamp).putInt("exit_reason", latest.reason).apply()
            }
        }.onFailure { Log.w("ProcessExit", "Could not read own process exit reason", it) }
    }

    fun taskRemoved(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putLong("task_time", System.currentTimeMillis()).commit()
    }

    fun protection(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putLong("service_time", System.currentTimeMillis())
            .putBoolean("service_started", running).commit()
    }

    fun summary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA)
        val exit = prefs.getLong("exit_time", 0)
        return buildString {
            append(when {
                Build.VERSION.SDK_INT < 30 -> "此系统不提供进程退出原因"
                exit == 0L -> "系统暂无进程退出记录"
                else -> "系统最近退出 ${format.format(Date(exit))}：${reasonLabel(prefs.getInt("exit_reason", 0))}"
            })
            val task = prefs.getLong("task_time", 0)
            if (task > 0) append("\n最近任务移除回调：${format.format(Date(task))}")
            val service = prefs.getLong("service_time", 0)
            if (service > 0) append("\n最近保护服务${if (prefs.getBoolean("service_started", false)) "启动" else "结束"}：${format.format(Date(service))}")
        }
    }

    internal fun reasonLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_USER_REQUESTED -> if (Build.VERSION.SDK_INT < 34)
            "用户操作、更新或组件变化（系统不能区分最近任务与强行停止）"
            else "用户操作结束（系统不能区分最近任务与强行停止）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统内存不足回收"
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "应用崩溃"
        ApplicationExitInfo.REASON_ANR -> "应用无响应"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变化"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用更新"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "应用组件状态变化"
        ApplicationExitInfo.REASON_USER_STOPPED -> "系统用户或用户资料已停止"
        ApplicationExitInfo.REASON_EXIT_SELF -> "进程自行结束"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "应用初始化失败"
        else -> "系统未提供可确定的原因"
    }
}
