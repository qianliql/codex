package com.example.lightledger.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object UpdateApkVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, info: UpdateInfo) {
        require(file.isFile && file.length() == info.size && sha256(file) == info.sha256) { "安装包校验失败，请重新下载" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("安装包无法读取")
        verifyIdentity(context.packageName, installed, archive, info)
    }
    @Suppress("DEPRECATION")
    internal fun verifyIdentity(packageName: String, installed: PackageInfo, archive: PackageInfo, info: UpdateInfo) {
        require(archive.packageName == packageName && archive.versionName == info.name) { "安装包与版本信息不符" }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(archiveCode == info.code && archiveCode > installedCode) { "安装包不是更新版本" }
        require(archive.applicationInfo?.minSdkVersion == info.minSdk && info.minSdk <= Build.VERSION.SDK_INT) { "当前系统不支持这个版本" }
        val currentCertificates = certificates(installed)
        require(currentCertificates.isNotEmpty() && certificates(archive) == currentCertificates) { "安装包签名不一致，已停止更新" }
    }
}
