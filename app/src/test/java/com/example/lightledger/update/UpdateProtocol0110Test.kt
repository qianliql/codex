package com.example.lightledger.update

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.robolectric.RuntimeEnvironment

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class UpdateProtocol0110Test {
    private val sha = "a".repeat(64)
    private fun release(): JSONObject = JSONObject().put("draft", false).put("prerelease", false)
        .put("tag_name", "v0.11.0").put("html_url", "https://github.com/qianliql/codex/releases/tag/v0.11.0")
        .put("assets", org.json.JSONArray().put(asset("app-update.json", 200)).put(asset("LightLedger-0.11.0-debug.apk", 3).put("digest", "sha256:$sha")))
    private fun asset(name: String, size: Int) = JSONObject().put("name", name).put("size", size).put("state", "uploaded")
        .put("browser_download_url", "https://github.com/qianliql/codex/releases/download/v0.11.0/$name")
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("versionName", "0.11.0").put("versionCode", 23)
        .put("minSdk", 26).put("apkName", "LightLedger-0.11.0-debug.apk").put("sha256", sha).put("size", 3).put("notes", "设置与应用内更新")
    private fun reject(block: () -> Unit) { assertTrue(runCatching(block).isFailure) }
    @Test fun stableVersionsAndReleaseAssetsMustAgree() {
        val value = UpdateSpec.decode(release(), manifest(), InstalledVersion("0.10.2", 22))
        assertEquals(23L, value.code); assertEquals(sha, value.sha256)
        assertTrue(UpdateSpec.compareVersions("0.10.2", "0.9.4") > 0)
        reject { UpdateSpec.decode(release(), manifest().put("versionCode", 22), InstalledVersion("0.10.2", 22)) }
        reject { UpdateSpec.decode(release(), manifest().put("versionName", "0.11.1"), InstalledVersion("0.10.2", 22)) }
        reject { UpdateSpec.decode(release(), manifest().put("size", 4), InstalledVersion("0.10.2", 22)) }
        reject { UpdateSpec.decode(release(), manifest().put("sha256", "b".repeat(64)), InstalledVersion("0.10.2", 22)) }
        reject { UpdateSpec.latestName(release().put("prerelease", true)) }
        reject { UpdateSpec.latestName(release().put("draft", true)) }
    }
    @Test fun onlyExactRepositoryAssetsAndHttpsHostsAreAccepted() {
        val bad = release(); bad.getJSONArray("assets").getJSONObject(1).put("browser_download_url", "https://github.com/other/repo/test.apk")
        reject { UpdateSpec.decode(bad, manifest(), InstalledVersion("0.10.2", 22)) }
        listOf("http://github.com/test", "https://github.com.evil.test/test", "https://evil.test/test",
            "https://user@github.com/test", "https://github.com:444/test", "https://github.com/test#fragment").forEach { assertFalse(it, UpdateSpec.trustedNetworkUrl(it)) }
        assertTrue(UpdateSpec.trustedNetworkUrl("https://release-assets.githubusercontent.com/test?key=sample"))
    }
    @Test fun legacyCurrentReleaseDoesNotRequireNewManifest() {
        var requests = 0
        val old = release().put("tag_name", "v0.10.2").put("assets", org.json.JSONArray())
        val gateway = GitHubUpdateGateway { requests++; Response(it, old.toString().toByteArray()) }
        assertNull(gateway.check(InstalledVersion("0.11.0", 23)))
        assertEquals(1, requests)
    }
    @Test fun redirectChecksAndMetadataLimitsApplyBeforeReading() {
        var calls = 0
        val gateway = GitHubUpdateGateway { calls++; Response(it, byteArrayOf(), 302, "https://evil.test/version") }
        reject { gateway.check(InstalledVersion("0.10.2", 22)) }; assertEquals(1, calls)
        val huge = GitHubUpdateGateway { Response(it, ByteArray(1024 * 1024 + 1)) }
        reject { huge.check(InstalledVersion("0.10.2", 22)) }
        val valid = GitHubUpdateGateway { url -> Response(url, if (url == UpdateSpec.API_URL) release().toString().toByteArray() else manifest().toString().toByteArray()) }
        assertEquals(23L, valid.check(InstalledVersion("0.10.2", 22))!!.code)
    }
    @Test fun incompleteAndOversizeDownloadFailWhileExactFileReportsProgress() {
        val info = UpdateSpec.decode(release(), manifest(), InstalledVersion("0.10.2", 22))
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "transport-test.part")
        try {
            listOf(byteArrayOf(1, 2), byteArrayOf(1, 2, 3, 4)).forEach { bytes ->
                reject { GitHubUpdateGateway { Response(it, bytes) }.download(info, file) {} }
            }
            val progress = mutableListOf<Long>()
            GitHubUpdateGateway { Response(it, byteArrayOf(1, 2, 3)) }.download(info, file) { progress.add(it) }
            assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes()); assertEquals(3L, progress.last())
        } finally { file.delete() }
    }
    @Test fun giteeMirrorFallsBackOnBadBytesAndUsesVerifiedMirrorWhenValid() {
        val good = byteArrayOf(1, 2, 3)
        val digest = MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it.toInt() and 255) }
        val mirror = "https://gitee.com/qianlihuo/money/releases/download/v0.11.1/LightLedger-0.11.1-debug.apk"
        val origin = "https://github.com/qianliql/codex/releases/download/v0.11.1/LightLedger-0.11.1-debug.apk"
        val info = UpdateInfo("0.11.1", 24, 26, origin, digest, good.size.toLong(), "镜像下载", UpdateSpec.RELEASE_PAGE, mirror)
        val file = File(RuntimeEnvironment.getApplication().cacheDir, "mirror-test.part")
        try {
            val requested = mutableListOf<String>()
            GitHubUpdateGateway { url -> requested.add(url); Response(url, if (url == mirror) byteArrayOf(3, 2, 1) else good) }
                .download(info, file) {}
            assertEquals(listOf(mirror, origin), requested)
            assertArrayEquals(good, file.readBytes())
            requested.clear()
            GitHubUpdateGateway { url -> requested.add(url); Response(url, good) }.download(info, file) {}
            assertEquals(listOf(mirror), requested)
            assertTrue(UpdateSpec.trustedNetworkUrl("https://foruda.gitee.com/attach_file/a/app.apk?token=abc"))
        } finally { file.delete() }
    }
    private class Response(url: String, private val body: ByteArray, private val code: Int = 200, private val location: String? = null) : HttpURLConnection(URL(url)) {
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String) = if (name == "Location") location else null
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun connect() {}
    }
}
