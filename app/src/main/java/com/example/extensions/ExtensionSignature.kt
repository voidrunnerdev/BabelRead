package com.example.extensions

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object ExtensionSignature {

    const val KEIYOUSHI_SIGNING_KEY = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2"

    @Suppress("DEPRECATION")
    fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION")
    fun apkCertificateSha256List(pkgInfo: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            when {
                signingInfo == null -> pkgInfo.signatures
                signingInfo.hasMultipleSigners() -> signingInfo.apkContentsSigners
                else -> signingInfo.apkContentsSigners
                    ?.takeIf { it.isNotEmpty() }
                    ?: signingInfo.signingCertificateHistory
            }
        } else {
            pkgInfo.signatures
        } ?: return emptyList()

        return signatures.map { sig ->
            MessageDigest.getInstance("SHA-256")
                .digest(sig.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    fun apkCertificateSha256(pkgInfo: PackageInfo): String? =
        apkCertificateSha256List(pkgInfo).firstOrNull()

    fun isTrusted(pkgInfo: PackageInfo): Boolean {
        val digests = apkCertificateSha256List(pkgInfo)
        return digests.isNotEmpty() && digests.all { it.equals(KEIYOUSHI_SIGNING_KEY, ignoreCase = true) }
    }
}
