package com.example.lightledger.update

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.URI

data class InstalledVersion(val name: String, val code: Long) {
    companion object {
        fun read(context: Context): InstalledVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return InstalledVersion(info.versionName.orEmpty(), if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
        }
    }
}

data class UpdateInfo(val name: String, val code: Long, val minSdk: Int, val apkUrl: String,
    val sha256: String, val size: Long, val notes: String, val pageUrl: String,
    val mirrorUrl: String? = null)

object UpdateSpec {
    const val API_URL = "https://api.github.com/repos/qianliql/codex/releases/latest"
    const val RELEASE_PAGE = "https://github.com/qianliql/codex/releases/latest"
    const val MANIFEST_NAME = "app-update.json"
    private val versionPattern = Regex("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")

    fun compareVersions(first: String, second: String): Int {
        require(versionPattern.matches(first) && versionPattern.matches(second)) { "版本信息无效" }
        val a = first.split('.').map(String::toInt); val b = second.split('.').map(String::toInt)
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }
    fun latestName(release: JSONObject): String {
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "暂未找到正式版本" }
        return release.getString("tag_name").removePrefix("v").also { require(versionPattern.matches(it)) }
    }
    private fun asset(release: JSONObject, name: String): JSONObject {
        val list = release.getJSONArray("assets")
        val matches = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.getString("name") == name }
        require(matches.size == 1 && matches.single().getString("state") == "uploaded") { "更新文件尚未准备好" }
        return matches.single()
    }
    fun releaseAssetUrl(release: JSONObject, name: String): String {
        val tag = "v${latestName(release)}"
        require(Regex("[a-zA-Z0-9._-]{1,100}").matches(name))
        val expected = "https://github.com/qianliql/codex/releases/download/$tag/$name"
        return asset(release, name).getString("browser_download_url").also { require(it == expected) { "更新地址无效" } }
    }
    fun decode(release: JSONObject, manifest: JSONObject, current: InstalledVersion): UpdateInfo {
        require(manifest.getInt("schemaVersion") == 1)
        val name = manifest.getString("versionName")
        require(name == latestName(release) && compareVersions(name, current.name) > 0) { "没有可用的新版本" }
        val code = manifest.getLong("versionCode")
        require(code > current.code && code <= Int.MAX_VALUE) { "更新版本号无效" }
        val minSdk = manifest.getInt("minSdk")
        require(minSdk in 26..100)
        val file = manifest.getString("apkName")
        require(file == "LightLedger-$name-debug.apk")
        val apkUrl = releaseAssetUrl(release, file)
        val digest = manifest.getString("sha256")
        require(Regex("[a-f0-9]{64}").matches(digest)) { "更新校验信息无效" }
        val size = manifest.getLong("size")
        require(size in 1..300L * 1024 * 1024 && size == asset(release, file).getLong("size")) { "更新文件大小无效" }
        val apiDigest = asset(release, file).optString("digest")
        require(apiDigest.isEmpty() || apiDigest == "sha256:$digest") { "更新校验信息不一致" }
        val page = "https://github.com/qianliql/codex/releases/tag/v$name"
        require(release.getString("html_url") == page)
        val notes = manifest.getString("notes").also { require(it.length in 1..8000) }
        val mirror = if (compareVersions(name, "0.11.1") >= 0)
            "https://gitee.com/qianlihuo/money/releases/download/v$name/$file" else null
        return UpdateInfo(name, code, minSdk, apkUrl, digest, size, notes, page, mirror)
    }
    fun trustedNetworkUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            (uri.port == -1 || uri.port == 443) && uri.host in setOf("api.github.com", "github.com",
                "release-assets.githubusercontent.com", "objects.githubusercontent.com", "gitee.com", "foruda.gitee.com")
    }.getOrDefault(false)
}
