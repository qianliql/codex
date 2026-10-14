package com.example.lightledger.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.example.lightledger.LedgerApp
import com.example.lightledger.R
import com.example.lightledger.data.LedgerDatabase
import com.example.lightledger.update.*
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Modifier
import java.time.Duration

@RunWith(com.example.lightledger.LedgerTestRunner::class)
@Config(application = LedgerApp::class, sdk = [29, 35], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsAndUpdate0110Test {
    private val app get() = RuntimeEnvironment.getApplication() as LedgerApp
    @Before fun prepare() {
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        ViewModelProvider.AndroidViewModelFactory::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers) && it.type == ViewModelProvider.AndroidViewModelFactory::class.java }
            .forEach { it.isAccessible = true; it.set(null, null) }
        app.getSharedPreferences("permission_guide", 0).edit().putBoolean("shown", true).commit()
    }
    @After fun close() {
        UpdateManager.resetForTests()
        File(app.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        LedgerDatabase.get(app).close()
        LedgerDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
    }
    @Test fun myPageHasOneSettingsEntryAndSettingsKeepBackupAndListener() {
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = main.get()
            activity.findViewById<BottomNavigationView>(R.id.bottomNavigation).selectedItemId = R.id.nav_settings
            activity.supportFragmentManager.executePendingTransactions()
            assertNotNull(activity.findViewById<View>(R.id.openSettings))
            assertNull(activity.findViewById<View>(R.id.exportBackup))
            assertEquals("轻账本 ${InstalledVersion.read(app).name}", activity.findViewById<TextView>(R.id.appVersion).text)
            activity.findViewById<View>(R.id.openSettings).performClick()
            assertEquals(SettingsActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
            render(activity, "my")
        } finally { main.pause().stop().destroy() }
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        try {
            val activity = settings.get(); activity.supportFragmentManager.executePendingTransactions()
            assertNotNull(activity.findViewById<View>(R.id.notificationSettings))
            val group = activity.findViewById<View>(R.id.exportBackup).parent as ViewGroup
            assertEquals(2, group.childCount)
            assertTrue(activity.findViewById<TextView>(R.id.currentVersion).text.contains(InstalledVersion.read(app).name))
            activity.findViewById<View>(R.id.checkForUpdates).performClick()
            assertEquals(AppUpdateActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
            render(activity, "settings")
            activity.findViewById<View>(R.id.goBack).performClick(); assertTrue(activity.isFinishing)
        } finally { settings.pause().stop().destroy() }
    }
    @Test fun downloadShowsNotesVerifiesPackageAndRequestsSystemPermission() {
        val content = byteArrayOf(1, 2, 3)
        val scratch = File(app.cacheDir, "hash-sample").apply { writeBytes(content) }
        val info = UpdateInfo("1.1.0", 27, 26, "https://github.com/qianliql/codex/releases/download/v1.1.0/LightLedger-1.1.0-debug.apk",
            UpdateApkVerifier.sha256(scratch), 3, "设置更简洁\n应用内下载更新", UpdateSpec.RELEASE_PAGE)
        scratch.delete()
        val pm = shadowOf(app.packageManager)
        val own = app.packageManager.getPackageInfo(app.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS or android.content.pm.PackageManager.GET_META_DATA or android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        own.signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("aabb"))) }
        pm.installPackage(own)
        val archive = PackageInfo().apply {
            packageName = app.packageName; versionName = info.name; versionCode = 27
            applicationInfo = ApplicationInfo().apply { minSdkVersion = 26 }
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature("aabb"))) }
        }
        UpdateManager.gateway = object : UpdateGateway {
            override fun check(current: InstalledVersion) = info
            override fun download(info: UpdateInfo, file: File, progress: (Long) -> Unit) {
                file.writeBytes(content); pm.setPackageArchiveInfo(file.absolutePath, archive); progress(3)
            }
        }
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).setup()
        try {
            val activity = controller.get()
            await { activity.findViewById<View>(R.id.downloadUpdate).isShown }
            assertTrue(activity.findViewById<TextView>(R.id.updateNotes).text.contains("应用内"))
            render(activity, "update")
            activity.findViewById<View>(R.id.downloadUpdate).performClick()
            await { activity.findViewById<View>(R.id.installUpdate).isShown }
            pm.setCanRequestPackageInstalls(false)
            activity.findViewById<View>(R.id.installUpdate).performClick()
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivityForResult.intent.action)
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, shadowOf(activity).nextStartedActivity.action)
            pm.setCanRequestPackageInstalls(true)
            val ready = UpdateManager.state.value as UpdateState.Ready
            pm.setPackageArchiveInfo(ready.file.absolutePath, archive)
            UpdateApkVerifier.verify(activity, ready.file, ready.info)
            val provider = app.packageManager.getProviderInfo(android.content.ComponentName(app, androidx.core.content.FileProvider::class.java),
                android.content.pm.PackageManager.GET_META_DATA)
            assertFalse(provider.exported); assertTrue(provider.grantUriPermissions)
            assertEquals(R.xml.update_file_paths, provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun smallScreenAndLargeTextKeepBackAndUpdateActionsUsable() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-xxhdpi"); RuntimeEnvironment.setFontScale(1.3f)
        val controller = Robolectric.buildActivity(AppUpdateActivity::class.java).setup()
        try {
            val activity = controller.get(); await { UpdateManager.state.value == UpdateState.Current }
            render(activity, "update-small")
            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val button = activity.findViewById<View>(R.id.checkForUpdates)
            assertTrue(button.width > 0 && button.width <= root.width)
            assertTrue(activity.findViewById<View>(R.id.goBack).width >= (48 * root.resources.displayMetrics.density).toInt())
            activity.findViewById<View>(R.id.goBack).performClick(); assertTrue(activity.isFinishing)
        } finally { controller.pause().stop().destroy(); RuntimeEnvironment.setFontScale(1f) }
    }
    @Test fun newVersionPromptDoesNotDependOnNotificationPermission() {
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", "")
        val future = UpdateInfo("1.1.0", 27, 26, "https://github.com/qianliql/codex/releases/download/v1.1.0/LightLedger-1.1.0-debug.apk",
            "a".repeat(64), 3, "更新说明", UpdateSpec.RELEASE_PAGE)
        UpdateManager.gateway = object : UpdateGateway {
            override fun check(current: InstalledVersion) = future
            override fun download(info: UpdateInfo, file: File, progress: (Long) -> Unit) = error("No automatic download")
        }
        val main = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            try { await { app.getSharedPreferences("app_updates", 0).getLong("last_prompted", 0) == 27L } }
            catch (error: AssertionError) { throw AssertionError("State=${UpdateManager.state.value}, prefs=${app.getSharedPreferences("app_updates", 0).all}", error) }
            assertEquals(27L, app.getSharedPreferences("app_updates", 0).getLong("last_prompted", 0))
            assertFalse(File(app.cacheDir, "updates/lightledger-27.apk").exists())
        } finally { main.pause().stop().destroy() }
    }
    private fun await(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5)); Thread.sleep(10) }
        assertTrue("UI updated", done())
    }
    private fun render(activity: android.app.Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val metrics = root.resources.displayMetrics
        fun force(view: View) { view.forceLayout(); if (view is ViewGroup) (0 until view.childCount).forEach { force(view.getChildAt(it)) } }
        repeat(3) {
            force(root); root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
        }
        System.getProperty("lightledger.preview.dir")?.let { folder ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap)); File(folder, "LightLedger-0.11.0-$name-api${RuntimeEnvironment.getApiLevel()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
