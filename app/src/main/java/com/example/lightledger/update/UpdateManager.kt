package com.example.lightledger.update

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object Current : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val bytes: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

object UpdateManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var activeJob: Job? = null
    internal var gateway: UpdateGateway = GitHubUpdateGateway()
    internal var verifyApk: (Context, File, UpdateInfo) -> Unit = UpdateApkVerifier::verify

    @Synchronized fun check(context: Context, manual: Boolean) {
        if (activeJob?.isCompleted == false) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis(); val last = prefs.getLong("last_check", 0)
        if (!manual && now >= last && last > 0 && now - last < 24 * 60 * 60 * 1000L) return
        mutableState.value = UpdateState.Checking
        activeJob = scope.launch {
            try {
                val info = gateway.check(InstalledVersion.read(app))
                mutableState.value = if (info == null) UpdateState.Current else {
                    val file = apkFile(app, info)
                    if (info.minSdk <= Build.VERSION.SDK_INT && file.isFile && runCatching { verifyApk(app, file, info) }.isSuccess)
                        UpdateState.Ready(info, file) else UpdateState.Available(info)
                }
                prefs.edit().putLong("last_check", now).apply()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.value = UpdateState.Failed("暂时无法检查更新，请重试或查看更新记录") }
        }
    }
    @Synchronized fun download(context: Context, info: UpdateInfo) {
        if (activeJob?.isCompleted == false) return
        if (info.minSdk > Build.VERSION.SDK_INT) {
            mutableState.value = UpdateState.Failed("当前系统不支持这个版本", info); return
        }
        val app = context.applicationContext
        mutableState.value = UpdateState.Downloading(info, 0)
        activeJob = scope.launch {
            var partial: File? = null
            try {
                val target = apkFile(app, info)
                val temp = File(target.parentFile, target.name + ".part")
                partial = temp
                val coroutine = currentCoroutineContext()
                gateway.download(info, temp) { bytes ->
                    coroutine.ensureActive()
                    mutableState.value = UpdateState.Downloading(info, bytes)
                }
                coroutine.ensureActive()
                verifyApk(app, temp, info)
                if (target.exists()) check(target.delete())
                check(temp.renameTo(target))
                mutableState.value = UpdateState.Ready(info, target)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutableState.value = UpdateState.Failed("下载或安装包验证失败，请重新下载", info) }
            finally { partial?.delete() }
        }
    }
    private fun apkFile(context: Context, info: UpdateInfo): File {
        val folder = File(context.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
        require(info.code in 1..Int.MAX_VALUE)
        return File(folder, "lightledger-${info.code}.apk")
    }
    internal fun resetForTests() {
        activeJob?.cancel(); activeJob = null
        mutableState.value = UpdateState.Idle
        verifyApk = UpdateApkVerifier::verify
    }
}
