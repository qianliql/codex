package com.example.lightledger.update

import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException

internal interface UpdateGateway {
    fun check(current: InstalledVersion): UpdateInfo?
    fun download(info: UpdateInfo, file: File, progress: (Long) -> Unit)
}

internal class GitHubUpdateGateway(private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection }) : UpdateGateway {
    private fun connection(url: String): HttpURLConnection {
        var next = url
        repeat(6) {
            require(UpdateSpec.trustedNetworkUrl(next)) { "更新地址无效" }
            val conn = open(next)
            conn.connectTimeout = 12_000; conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "LightLedger-updater")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Accept-Encoding", "identity")
            try {
                when (val status = conn.responseCode) {
                    200 -> return conn
                    301, 302, 303, 307, 308 -> {
                        val location = conn.getHeaderField("Location") ?: error("更新地址不可用")
                        next = URI(next).resolve(location).toString()
                        conn.disconnect()
                    }
                    else -> error(if (status == 403 || status == 429) "更新检查暂受限制，请稍后重试" else "暂时无法连接更新服务")
                }
            } catch (error: Exception) { conn.disconnect(); throw error }
        }
        error("更新地址跳转过多")
    }
    private fun json(url: String, maxBytes: Int): JSONObject {
        val conn = connection(url)
        try {
            val bytes = conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= maxBytes) { "版本信息过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { conn.disconnect() }
    }
    override fun check(current: InstalledVersion): UpdateInfo? {
        val release = json(UpdateSpec.API_URL, 1024 * 1024)
        if (UpdateSpec.compareVersions(UpdateSpec.latestName(release), current.name) <= 0) return null
        val manifest = json(UpdateSpec.releaseAssetUrl(release, UpdateSpec.MANIFEST_NAME), 64 * 1024)
        return UpdateSpec.decode(release, manifest, current)
    }
    override fun download(info: UpdateInfo, file: File, progress: (Long) -> Unit) {
        var firstFailure: Exception? = null
        for (url in listOfNotNull(info.mirrorUrl, info.apkUrl)) {
            try {
                downloadFrom(url, info, file, progress)
                if (url == info.mirrorUrl) require(UpdateApkVerifier.sha256(file) == info.sha256) { "镜像文件校验失败" }
                return
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val previousFailure = firstFailure
                if (previousFailure == null) firstFailure = error
                file.delete()
                if (url == info.apkUrl) {
                    if (previousFailure != null) error.addSuppressed(previousFailure)
                    throw error
                }
                android.util.Log.w("LightLedgerUpdate", "Gitee mirror failed; trying GitHub", error)
                progress(0)
            }
        }
        throw firstFailure ?: IllegalStateException("更新地址不可用")
    }
    private fun downloadFrom(url: String, info: UpdateInfo, file: File, progress: (Long) -> Unit) {
        val conn = connection(url)
        try {
            var bytes = 0L
            conn.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= info.size) { "更新文件大小不一致" }
                    progress(bytes)
                    output.write(buffer, 0, count)
                }
            } }
            require(bytes == info.size) { "更新文件下载不完整" }
        } finally { conn.disconnect() }
    }
}
