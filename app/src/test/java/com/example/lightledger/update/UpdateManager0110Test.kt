package com.example.lightledger.update

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = Application::class, sdk = [26, 35])
class UpdateManager0110Test {
    private val app get() = RuntimeEnvironment.getApplication()
    private val info get() = UpdateInfo("0.12.0", 24, 26, "https://github.com/qianliql/codex/releases/download/v0.12.0/LightLedger-0.12.0-debug.apk", "a".repeat(64), 3, "更新说明", UpdateSpec.RELEASE_PAGE)
    @After fun close() { UpdateManager.resetForTests(); File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() } }
    @Test fun startupIsThrottledAndManualCheckCanRetryWithoutDownloading() {
        var checks = 0; var downloads = 0
        UpdateManager.gateway = fake({ checks++; info }) { file -> downloads++; file.writeBytes(byteArrayOf(1, 2, 3)) }
        UpdateManager.check(app, false); await { UpdateManager.state.value is UpdateState.Available }; Thread.sleep(20)
        UpdateManager.check(app, false); Thread.sleep(50); assertEquals(1, checks); assertEquals(0, downloads)
        UpdateManager.check(app, true); await { checks == 2 && UpdateManager.state.value is UpdateState.Available }
        assertEquals(0, downloads)
    }
    @Test fun verificationFailureRemovesPartialAndSuccessfulRetryCachesFile() {
        UpdateManager.gateway = fake({ info }) { it.writeBytes(byteArrayOf(1, 2, 3)) }
        UpdateManager.verifyApk = { _, _, _ -> error("Wrong signature") }
        UpdateManager.download(app, info); await { UpdateManager.state.value is UpdateState.Failed }; Thread.sleep(20)
        assertFalse(File(app.cacheDir, "updates/lightledger-24.apk").exists())
        assertFalse(File(app.cacheDir, "updates/lightledger-24.apk.part").exists())
        UpdateManager.verifyApk = { _, file, _ -> assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes()) }
        UpdateManager.download(app, info); await { UpdateManager.state.value is UpdateState.Ready }; Thread.sleep(20)
        assertEquals(3L, (UpdateManager.state.value as UpdateState.Ready).file.length())
        UpdateManager.check(app, true); await { UpdateManager.state.value is UpdateState.Ready }
    }
    @Test fun incompatibleSystemsAndStorageFailureCannotStartInstallation() {
        var downloads = 0
        UpdateManager.gateway = fake({ info }) { downloads++ }
        UpdateManager.download(app, info.copy(minSdk = 99)); assertTrue(UpdateManager.state.value is UpdateState.Failed); assertEquals(0, downloads)
        File(app.cacheDir, "updates").apply { listFiles()?.forEach { it.delete() }; delete(); writeText("blocked") }
        UpdateManager.download(app, info); await { UpdateManager.state.value is UpdateState.Failed }
        assertEquals(0, downloads); File(app.cacheDir, "updates").delete()
    }
    @Test fun packageSignerVersionAndSystemMustMatchBeforeInstaller() {
        val own = packageInfo("com.example.lightledger", "0.11.0", 23, "aabb")
        val apk = packageInfo("com.example.lightledger", "0.12.0", 24, "aabb")
        UpdateApkVerifier.verifyIdentity(own.packageName, own, apk, info)
        listOf(packageInfo("other.package", "0.12.0", 24, "aabb"), packageInfo(own.packageName, "0.12.0", 24, "ccdd"),
            packageInfo(own.packageName, "0.12.0", 23, "aabb"), packageInfo(own.packageName, "0.13.0", 24, "aabb")).forEach { bad ->
            assertTrue(runCatching { UpdateApkVerifier.verifyIdentity(own.packageName, own, bad, info) }.isFailure)
        }
        assertTrue(runCatching { UpdateApkVerifier.verifyIdentity(own.packageName, own, apk, info.copy(minSdk = 99)) }.isFailure)
        val file = File(app.cacheDir, "bad-apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try { assertTrue(runCatching { UpdateApkVerifier.verify(app, file, info) }.isFailure) } finally { file.delete() }
    }
    private fun packageInfo(name: String, version: String, code: Int, signature: String) = PackageInfo().apply {
        packageName = name; versionName = version; versionCode = code
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 26 }
        val signs = arrayOf(Signature(signature))
        if (Build.VERSION.SDK_INT >= 28) signingInfo = SigningInfo().also { shadowOf(it).setSignatures(signs) } else signatures = signs
    }
    private fun fake(check: () -> UpdateInfo?, download: (File) -> Unit) = object : UpdateGateway {
        override fun check(current: InstalledVersion) = check()
        override fun download(info: UpdateInfo, file: File, progress: (Long) -> Unit) { download(file); progress(file.length()) }
    }
    private fun await(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Update work completed", done())
    }
}
