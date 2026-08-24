package com.sahidcode404.camx.core.update.verification

import android.content.Context
import android.os.Build
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

class AndroidApkInspector(private val context: Context) {
    internal fun verifiedUpdateDirectory(): File =
        File(context.cacheDir, DevOtaTrust.VERIFIED_UPDATE_RELATIVE_DIRECTORY).canonicalFile

    fun inspectInstalled(): InstalledAppIdentity {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signers = checkNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1) { "Installed development app must have exactly one current signer" }
        return InstalledAppIdentity(
            applicationId = info.packageName,
            versionCode = info.longVersionCode,
            signingCertSha256 = sha256Hex(signers.single().toByteArray().inputStream()),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    fun inspect(apk: File): DownloadedApkIdentity {
        require(apk.isFile) { "APK does not exist" }
        require(apk.length() in 1..DevOtaTrust.MAX_APK_BYTES) { "APK size is outside bounds" }
        @Suppress("DEPRECATION")
        val info = checkNotNull(
            context.packageManager.getPackageArchiveInfo(
                apk.path,
                PackageManager.GET_SIGNING_CERTIFICATES,
            ),
        ) {
            "Android could not inspect the downloaded APK"
        }
        val signers = checkNotNull(info.signingInfo).apkContentsSigners
        check(signers.size == 1) { "Development APK must have exactly one current signer" }
        return DownloadedApkIdentity(
            applicationId = info.packageName,
            versionCode = info.longVersionCode,
            versionName = checkNotNull(info.versionName) { "APK version name is missing" },
            minSdk = checkNotNull(info.applicationInfo) { "APK application metadata is missing" }
                .minSdkVersion,
            sha256 = apk.inputStream().use(::sha256Hex),
            signingCertSha256 = sha256Hex(signers.single().toByteArray().inputStream()),
        )
    }

    private fun sha256Hex(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
